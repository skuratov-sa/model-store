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
    private String HTML_BODY;
//    private final static String HTML_BODY = """
//        <html>
//            <body>
//                <p>Ваш код подтверждения:</p>
//                <h1 style="font-size: 24px; font-weight: bold;">%s</h1>
//                <p>Пожалуйста, введите этот код для подтверждения вашей почты.</p>
//            </body>
//        </html>
//        """;


    @PostConstruct
    public void init() throws IOException {
        // Загрузка HTML шаблона
        ClassPathResource templateResource = new ClassPathResource("templates/email-template.html");
        this.HTML_BODY = FileCopyUtils.copyToString(
                new InputStreamReader(templateResource.getInputStream(), StandardCharsets.UTF_8)
        );
    }


    public Mono<Void> sendVerificationCode(Long participantId, String email) {
        String code = generateCode();

        return sendHtmlEmail(email, "Подтверждение почты", HTML_BODY.formatted(code))
                .then(verificationCodeService.addCode(participantId, code))
                .onErrorResume(e -> {
                    log.error("Не удалось отправить email {}: {}", email, e.getCause());
                    return Mono.error(new IllegalAccessError("Ошибка отправки кода для подтверждения: " + e));
                });
    }

    private Mono<Object> sendHtmlEmail(String to, String subject, String htmlText) {

        return Mono.fromCallable(() -> {
                    MimeMessage message = mailSender.createMimeMessage();
                    MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                    helper.setTo(to);
                    helper.setSubject(subject);
                    helper.setFrom(properties.getEmailFrom());
                    helper.setText(htmlText, true);
                    return message;
                })
                .subscribeOn(Schedulers.boundedElastic()) // Выносим блокирующую операцию
                .flatMap(message -> Mono.fromCallable(() -> {
                                    mailSender.send(message);
                                    return null;
                                })
                                .subscribeOn(Schedulers.boundedElastic())
                                .timeout(Duration.ofSeconds(100)) // Таймаут на отправку
                ).doOnError(e -> log.error("Failed to send email to {}", to, e));


//        return Mono.fromCallable(() -> {
//            try {
//                MimeMessage message = mailSender.createMimeMessage();
//                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
//
//                helper.setTo(to);
//                helper.setSubject(subject);
//                helper.setFrom(properties.getEmailFrom());
//                helper.setText(htmlText, true); // true указывает на HTML-контент
//                mailSender.send(message);
//            } catch (MessagingException e) {
//                throw new RuntimeException("Failed to send email", e);
//            }
//        });
    }

//    @Override
//    public Mono<Void> sendVerificationCode(String token, String email) {
//        return sendEmail(email, "Подтверждение почты", "Введите код подтверждения: " + generateCode());
//    }
//
//    private Mono<Void> sendEmail(String to, String subject, String text) {
//        return Mono.fromRunnable(() -> {
//            SimpleMailMessage message = new SimpleMailMessage();
//            message.setTo(to);
//            message.setSubject(subject);
//            message.setFrom(properties.getEmailFrom());
//            message.setText(text);
//            mailSender.send(message);
//        });
//    }
}

