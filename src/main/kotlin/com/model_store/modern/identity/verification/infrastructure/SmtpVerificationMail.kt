package com.model_store.modern.identity.verification.infrastructure

import com.model_store.modern.identity.verification.application.VerificationMail
import jakarta.mail.internet.MimeMessage
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ClassPathResource
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component

@Component
@Profile("modern")
class SmtpVerificationMail(
    private val sender: JavaMailSender,
    @Value("\${app.email-from}") private val from: String,
    @Value("\${app.email-reply-to:}") private val replyTo: String,
) : VerificationMail {
    private val verificationTemplate by lazy { template("templates/email-template.html") }
    private val passwordTemplate by lazy { template("templates/password-reset-template.html") }

    override fun sendVerification(mail: String, code: String) =
        send(mail, "Подтверждение почты", verificationTemplate.format(code))

    override fun sendPasswordReset(mail: String, password: String) =
        send(mail, "Ваш новый пароль", passwordTemplate.format(password))

    private fun send(to: String, subject: String, html: String) {
        val message: MimeMessage = sender.createMimeMessage()
        MimeMessageHelper(message, true, "UTF-8").apply {
            setTo(to)
            setSubject(subject)
            setFrom(from)
            if (replyTo.isNotBlank()) setReplyTo(replyTo)
            setText(html, true)
        }
        sender.send(message)
    }

    private fun template(path: String) = ClassPathResource(path).inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
