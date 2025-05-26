package com.model_store.service.impl;

import com.model_store.service.VerificationCodeService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class VerificationCodeServiceImpl implements VerificationCodeService {
    private final Map<Long, String> codeStorage = new ConcurrentHashMap<>();

    public Mono<Void> addCode(Long userId, String code) {
        return Mono.fromRunnable(() -> codeStorage.put(userId, code));
    }

    @Override
    public Mono<Void> verifyCode(Long userId, String code) {
        return Mono.defer(() -> {
            String storedCode = codeStorage.get(userId);
            if (storedCode != null && storedCode.equals(code)) {
                codeStorage.remove(userId);
                return Mono.empty();
            }
            return Mono.error(new RuntimeException("Invalid verification code"));
        });
    }

    public static String generateCode() {
        Random random = new Random();
        int code = random.nextInt(90000) + 10000; // Диапазон 10000-99999
        return String.valueOf(code);
    }
}
