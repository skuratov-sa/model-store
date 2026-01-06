package com.model_store.service.impl;

import com.model_store.configuration.property.ApplicationProperties;
import com.model_store.service.EmailService;
import jakarta.annotation.PostConstruct;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.model_store.service.impl.VerificationCodeServiceImpl.generateCode;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {
    private final JavaMailSender mailSender;
    private final ApplicationProperties properties;
    private final VerificationCodeServiceImpl verificationCodeService;
    private final ParticipantServiceImpl participantService;

    private String VERIFICATION_CODE_BODY;
    private String PASSWORD_RESET_BODY;

    @PostConstruct
    public void init() throws IOException {
        this.VERIFICATION_CODE_BODY = FileCopyUtils.copyToString(
                new InputStreamReader(new ClassPathResource("templates/email-template.html").getInputStream(), StandardCharsets.UTF_8)
        );
        this.PASSWORD_RESET_BODY = FileCopyUtils.copyToString(
                new InputStreamReader(new ClassPathResource("templates/password-reset-template.html").getInputStream(), StandardCharsets.UTF_8)
        );
    }

    @Override
    public Mono<Long> sendVerificationCode(String email) {
        return participantService.findByMail(email)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Пользователя с такой почтой не существует")))
                .flatMap(p ->
                        verificationCodeService.enforceSendLimits(p.getId())
                                .then(Mono.defer(() -> {
                                    String code = generateCode();
                                    return sendHtmlEmail(email, "Подтверждение почты", VERIFICATION_CODE_BODY.formatted(code))
                                            .then(verificationCodeService.addCode(p.getId(), code));
                                })).thenReturn(p.getId())
                );
    }

    @Override
    public Mono<Long> sendVerificationWithoutLimitCode(String email) {
        return participantService.findByMail(email)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Пользователя с такой почтой не существует")))
                .flatMap(p ->
                        Mono.defer(() -> {
                            String code = generateCode();
                            return sendHtmlEmail(email, "Подтверждение почты", VERIFICATION_CODE_BODY.formatted(code))
                                    .then(verificationCodeService.addCode(p.getId(), code));
                        }).thenReturn(p.getId())
                );
    }

    @Override
    public Mono<Void> sendPasswordReset(String email) {
        return Mono.defer(() ->
                participantService.findByMail(email)
                        .switchIfEmpty(Mono.error(new IllegalArgumentException("Пользователя с такой почтой не существует")))
                        .flatMap(p ->
                                verificationCodeService.enforceSendLimits(p.getId())
                                        .then(participantService.resetAndUpdateTemplatePassword(p.getId())))
                        .flatMap(tempPassword ->
                                sendHtmlEmail(email, "Ваш новый пароль", PASSWORD_RESET_BODY.formatted(tempPassword))
                        )
                        .then()
        );
    }

    private Mono<Void> sendHtmlEmail(String to, String subject, String htmlText) {
        return Mono.fromCallable(() -> {
                    MimeMessage message = mailSender.createMimeMessage();
                    MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                    helper.setTo(to);
                    helper.setSubject(subject);
                    helper.setFrom(properties.getEmailFrom());
                    helper.setText(htmlText, true);
                    mailSender.send(message);
                    return null; // обязательно
                })
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(100))
                .doOnError(e -> log.error("Ошибка отправки кода для подтверждения {}", to, e))
                .then();
    }
}

