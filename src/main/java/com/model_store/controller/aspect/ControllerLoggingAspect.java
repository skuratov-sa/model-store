package com.model_store.controller.aspect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Aspect
@Component
@Slf4j
public class ControllerLoggingAspect {

    private static final String MDC_TRACE_ID_KEY = "traceId";
    private static final int MAX_BODY_LENGTH = 2000;
    private static final Set<String> SENSITIVE_FIELDS = Set.of("authorization", "password", "token");

    @Around("@annotation(io.swagger.v3.oas.annotations.Operation)")
    public Object logController(ProceedingJoinPoint joinPoint) throws Throwable {
        String traceId = MDC.get(MDC_TRACE_ID_KEY);
        if (traceId == null) traceId = UUID.randomUUID().toString();
        MDC.put(MDC_TRACE_ID_KEY, traceId);

        // Логируем входящий вызов
        String methodName = joinPoint.getSignature().toShortString();
        Object[] args = joinPoint.getArgs();

        String argString = Arrays.stream(args)
                .map(arg -> maskSensitive(arg))
                .map(s -> s.length() > MAX_BODY_LENGTH ? s.substring(0, MAX_BODY_LENGTH) + "...(truncated)" : s)
                .collect(Collectors.joining(", "));

        log.info("Incoming call [{}] [{}]: {}", traceId, methodName, argString);

        try {
            Object result = joinPoint.proceed();
            log.info("Completed call [{}] [{}]", traceId, methodName);
            return result;
        } catch (Throwable t) {
            log.error("Exception in [{}] [{}]: {}", traceId, methodName, t.toString(), t);
            throw t;
        } finally {
            MDC.remove(MDC_TRACE_ID_KEY);
        }
    }

    // Метод для маскировки чувствительных полей
    private String maskSensitive(Object obj) {
        if (obj == null) return "null";

        // если это строка, проверяем, начинается ли с "Bearer "
        if (obj instanceof String str) {
            if (str.startsWith("Bearer ")) return "***masked***";
            return str;
        }

        // иначе пробуем JSON
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.valueToTree(obj);
            maskNode(node);
            return mapper.writeValueAsString(node);
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
