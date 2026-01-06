package com.model_store.service.impl;

import com.model_store.exception.TooManyRequestsException;
import com.model_store.service.VerificationCodeService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class VerificationCodeServiceImpl implements VerificationCodeService {
    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final Duration COOLDOWN = Duration.ofSeconds(30);
    private static final int DAILY_LIMIT = 30;

    private final ConcurrentHashMap<Long, CodeEntry> codeStorage = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, RateEntry> rateStorage = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Object> locks = new ConcurrentHashMap<>();


    @Override
    public Mono<Void> addCode(Long userId, String code) {
        return Mono.fromRunnable(() -> codeStorage.put(userId, new CodeEntry(code, System.currentTimeMillis())))
                .then();
    }

    @Override
    public Mono<Void> checkAndConsumeCode(Long userId, String code) {
        return Mono.fromRunnable(() -> {
            CodeEntry entry = codeStorage.get(userId);
            if (entry == null) {
                throw new IllegalArgumentException("Код не найден или истёк");
            }
            long now = System.currentTimeMillis();
            if (now - entry.createdAtMs > CODE_TTL.toMillis()) {
                codeStorage.remove(userId);
                throw new IllegalArgumentException("Код истёк");
            }
            if (!entry.code.equals(code)) {
                throw new IllegalArgumentException("Неверный код");
            }
            codeStorage.remove(userId); // одноразовый
        }).then();
    }

    /** Вызывать ПЕРЕД отправкой email: проверяет лимиты и обновляет счетчики атомарно */
    @Override
    public Mono<Void> enforceSendLimits(Long userId) {
        return Mono.fromRunnable(() -> {
            Object lock = locks.computeIfAbsent(userId, k -> new Object());
            synchronized (lock) {
                long now = System.currentTimeMillis();

                RateEntry rate = rateStorage.computeIfAbsent(userId, k -> new RateEntry(now, now, 0));

                // суточное окно (скользящее 24ч)
                if (now - rate.windowStartMs >= Duration.ofHours(24).toMillis()) {
                    rate.windowStartMs = now;
                    rate.sentInWindow = 0;
                }

                // cooldown
                if (now - rate.lastSentAtMs < COOLDOWN.toMillis()) {
                    long retryMs = COOLDOWN.toMillis() - (now - rate.lastSentAtMs);
                    throw new TooManyRequestsException("VERIFICATION_COOLDOWN", (int) (retryMs / 1000));
                }

                // дневной лимит
                if (rate.sentInWindow >= DAILY_LIMIT) {
                    throw new TooManyRequestsException("VERIFICATION_DAILY_LIMIT", -1);
                }

                // фиксируем факт отправки (важно: внутри lock)
                rate.lastSentAtMs = now;
                rate.sentInWindow++;
            }
        }).then();
    }

    public static String generateCode() {
        Random random = new Random();
        int code = random.nextInt(90000) + 10000; // Диапазон 10000-99999
        return String.valueOf(code);
    }

    private static final class CodeEntry {
        final String code;
        final long createdAtMs;
        CodeEntry(String code, long createdAtMs) {
            this.code = code;
            this.createdAtMs = createdAtMs;
        }
    }
    private static final class RateEntry {
        long lastSentAtMs;
        long windowStartMs;
        int sentInWindow;

        RateEntry(long lastSentAtMs, long windowStartMs, int sentInWindow) {
            this.lastSentAtMs = lastSentAtMs;
            this.windowStartMs = windowStartMs;
            this.sentInWindow = sentInWindow;
        }
    }
}
