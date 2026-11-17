
        package com.example.intern.service;

import com.example.intern.model.PasswordResetToken;
import com.example.intern.model.Student;
import com.example.intern.model.Teacher;
import com.example.intern.repository.PasswordResetTokenRepository;
import com.example.intern.repository.StudentRepository;
import com.example.intern.repository.TeacherRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

@Service
public class PasswordResetService {

    private final StudentRepository studentRepository;
    private final TeacherRepository teacherRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final JavaMailSender mailSender;
    private final PasswordEncoder passwordEncoder;

    @Value("${spring.mail.username}")
    private String senderEmail;

    @Value("${app.frontend.base-url:http://localhost:8080}")
    private String frontendBaseUrl;

    public PasswordResetService(
            StudentRepository studentRepository,
            TeacherRepository teacherRepository,
            PasswordResetTokenRepository tokenRepository,
            JavaMailSender mailSender,
            PasswordEncoder passwordEncoder) {

        this.studentRepository = studentRepository;
        this.teacherRepository = teacherRepository;
        this.tokenRepository = tokenRepository;
        this.mailSender = mailSender;
        this.passwordEncoder = passwordEncoder;
    }

    public void requestReset(String email) {

        if (email == null || email.isBlank()) {
            return;
        }

        String username = email.trim();

        Optional<Student> student =
                studentRepository.findByUsername(username);
        Optional<Teacher> teacher =
                teacherRepository.findByUsername(username);

        String accountType;
        String recipient;

        if (student.isPresent()) {
            accountType = "STUDENT";
            recipient = student.get().getUsername();
        } else if (teacher.isPresent()) {
            accountType = "TEACHER";
            recipient = teacher.get().getUsername();
        } else {
            return;
        }

        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);

        String rawToken = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        String tokenHash = sha256(rawToken);

        tokenRepository.deleteByUsernameAndAccountType(
                username, accountType);

        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setUsername(username);
        resetToken.setAccountType(accountType);
        resetToken.setTokenHash(tokenHash);
        resetToken.setExpiresAt(Instant.now().plusSeconds(15 * 60));
        resetToken.setUsed(false);

        tokenRepository.save(resetToken);

        String resetLink = frontendBaseUrl
                + "/reset-password.html?token=" + rawToken;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(senderEmail);
        message.setTo(recipient);
        message.setSubject("IntelliGen Password Reset");
        message.setText(
                "A password reset was requested for your IntelliGen account.\n\n"
                        + "Use this link within 15 minutes:\n"
                        + resetLink
                        + "\n\nIf you did not request this, ignore this email."
        );

        mailSender.send(message);
    }

    @Transactional
    public boolean resetPassword(
            String rawToken, String newPassword) {

        if (rawToken == null || rawToken.isBlank()
                || newPassword == null
                || newPassword.length() < 8
                || newPassword.length() > 72) {
            return false;
        }

        String tokenHash = sha256(rawToken);

        Optional<PasswordResetToken> tokenOptional =
                tokenRepository.findByTokenHash(tokenHash);

        if (tokenOptional.isEmpty()) {
            return false;
        }

        PasswordResetToken resetToken = tokenOptional.get();

        if (resetToken.isUsed()
                || !resetToken.getExpiresAt().isAfter(Instant.now())) {
            return false;
        }

        String encodedPassword = passwordEncoder.encode(newPassword);

        if ("STUDENT".equals(resetToken.getAccountType())) {

            Optional<Student> student =
                    studentRepository.findByUsername(
                            resetToken.getUsername());

            if (student.isEmpty()) {
                return false;
            }

            Student account = student.get();
            account.setPassword(encodedPassword);
            studentRepository.save(account);

        } else if ("TEACHER".equals(resetToken.getAccountType())) {

            Optional<Teacher> teacher =
                    teacherRepository.findByUsername(
                            resetToken.getUsername());

            if (teacher.isEmpty()) {
                return false;
            }

            Teacher account = teacher.get();
            account.setPassword(encodedPassword);
            teacherRepository.save(account);

        } else {
            return false;
        }

        tokenRepository.deleteByUsernameAndAccountType(
                resetToken.getUsername(),
                resetToken.getAccountType());

        return true;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    input.getBytes(StandardCharsets.UTF_8));

            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(hash);

        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Unable to process reset token.", ex);
        }
    }
}
