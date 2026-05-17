package com.model_store.controller.aspect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.model_store.logging.MdcThreadLocalAccessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class ControllerLoggingAspect {

    private static final String MDC_TRACE_ID_KEY = "traceId";
    private static final int MAX_BODY_LENGTH = 2000;
    private static final Set<String> SENSITIVE_FIELDS = Set.of("authorization", "password", "token");

    private final ObjectMapper objectMapper;

    @Around("@annotation(io.swagger.v3.oas.annotations.Operation)")
    public Object logController(ProceedingJoinPoint joinPoint) throws Throwable {
        String traceId = UUID.randomUUID().toString();
        MDC.put(MDC_TRACE_ID_KEY, traceId);

        String methodName = joinPoint.getSignature().toShortString();
        Object[] args = joinPoint.getArgs();

        String argString = Arrays.stream(args)
                .map(this::maskSensitive)
                .map(s -> s.length() > MAX_BODY_LENGTH ? s.substring(0, MAX_BODY_LENGTH) + "...(truncated)" : s)
                .collect(Collectors.joining(", "));

        log.info("Incoming call [{}] [{}]: {}", traceId, methodName, argString);

        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
        MDC.remove(MDC_TRACE_ID_KEY);

        Object result = joinPoint.proceed();

        if (result instanceof Mono<?> mono) {
            return mono
                    .doOnSuccess(r -> log.info("Completed call [{}] [{}]", traceId, methodName))
                    .doOnError(t -> log.error("Exception in [{}] [{}]: {}", traceId, methodName, t.toString(), t))
                    .contextWrite(ctx -> mdcSnapshot != null
                            ? ctx.put(MdcThreadLocalAccessor.KEY, mdcSnapshot)
                            : ctx);
        }

        return result;
    }

    private String maskSensitive(Object obj) {
        if (obj == null) return "null";

        if (obj instanceof String str) {
            if (str.startsWith("Bearer ")) return "***masked***";
            return str;
        }

        try {
            JsonNode node = objectMapper.valueToTree(obj);
            maskNode(node);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    private void maskNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode objNode = (ObjectNode) node;
            objNode.fieldNames().forEachRemaining(field -> {
                if (SENSITIVE_FIELDS.contains(field.toLowerCase())) {
                    objNode.put(field, "***masked***");
                } else {
                    maskNode(objNode.get(field));
                }
            });
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                maskNode(item);
            }
        }
    }
}
