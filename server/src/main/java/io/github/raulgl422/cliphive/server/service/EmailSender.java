package io.github.raulgl422.cliphive.server.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Sends the superadmin's email alerts over SMTP — docs/DESIGN.md §12.3.
 *
 * <p>Built from the {@code CLIPHIVE_SMTP_*} variables instead of Spring Boot's
 * {@code spring.mail.*} auto-configuration, so an empty host simply means
 * "alerts off" and port 465 (implicit SSL) works as well as 587 (STARTTLS).
 * Any SMTP provider fits; the deployment uses Resend ({@code smtp.resend.com},
 * user {@code resend}, password = API key).
 */
@Component
public class EmailSender {

    private static final int TIMEOUT_MS = 15_000;

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String from;
    private volatile JavaMailSenderImpl sender;

    public EmailSender(@Value("${cliphive.smtp.host:}") String host,
                       @Value("${cliphive.smtp.port:587}") int port,
                       @Value("${cliphive.smtp.user:}") String user,
                       @Value("${cliphive.smtp.password:}") String password,
                       @Value("${cliphive.smtp.from:}") String from) {
        this.host = host.strip();
        this.port = port;
        this.user = user.strip();
        this.password = password;
        this.from = from.strip();
    }

    /** True when there's a server and a sender address to send from. */
    public boolean isConfigured() {
        return !host.isEmpty() && !from.isEmpty();
    }

    /**
     * Sends a plain-text email.
     *
     * @throws IllegalStateException if SMTP isn't configured
     * @throws org.springframework.mail.MailException if the server rejects it or can't be reached
     */
    public void send(String to, String subject, String text) {
        if (!isConfigured()) {
            throw new IllegalStateException("SMTP sin configurar (CLIPHIVE_SMTP_HOST y CLIPHIVE_SMTP_FROM)");
        }
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        sender().send(message);
    }

    private JavaMailSenderImpl sender() {
        if (sender == null) {
            synchronized (this) {
                if (sender == null) sender = build();
            }
        }
        return sender;
    }

    private JavaMailSenderImpl build() {
        var s = new JavaMailSenderImpl();
        s.setHost(host);
        s.setPort(port);
        s.setDefaultEncoding("UTF-8");
        Properties props = s.getJavaMailProperties();
        if (!user.isEmpty()) {
            s.setUsername(user);
            s.setPassword(password);
            props.put("mail.smtp.auth", "true");
        }
        if (port == 465) {
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
        }
        props.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MS));
        props.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MS));
        props.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MS));
        return s;
    }
}
