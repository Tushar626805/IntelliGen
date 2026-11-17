package com.example.intern.controller;

import com.example.intern.security.RateLimiter;
import com.example.intern.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/auth")
public class PasswordResetController {

    private static final long FIFTEEN_MINUTES = 15 * 60 * 1000L;
    private static final String GENERIC_MESSAGE = "If an account exists for that email, a reset link will be sent.";

    private final PasswordResetService passwordResetService;
    private final RateLimiter rateLimiter;

    public PasswordResetController(PasswordResetService passwordResetService, RateLimiter rateLimiter) {
        this.passwordResetService = passwordResetService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody Map<String, String> payload, HttpServletRequest request) {
        String email = payload.get("email");
        String normalized = email == null ? "" : email.trim().toLowerCase();

        // Rate limits: per IP and per target email. Over-limit requests get the SAME generic answer,
        // so the limiter cannot be used to probe which accounts exist.
        boolean allowed = rateLimiter.allow("forgot-ip:" + request.getRemoteAddr(), 10, FIFTEEN_MINUTES)
                && rateLimiter.allow("forgot-email:" + normalized, 3, FIFTEEN_MINUTES);

        if (allowed && !normalized.isEmpty()) {
            // Run asynchronously so the response time is identical whether or not the account exists
            // (sending mail is slow; an instant reply would reveal "no such account").
            CompletableFuture.runAsync(() -> {
                try {
                    passwordResetService.requestReset(email);
                } catch (RuntimeException ex) {
                    // Swallowed on purpose: never reveal account details or mail failures to the caller.
                }
            });
        }
        return ResponseEntity.ok(Map.of("message", GENERIC_MESSAGE));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> payload, HttpServletRequest request) {
        if (!rateLimiter.allow("reset-ip:" + request.getRemoteAddr(), 20, FIFTEEN_MINUTES)) {
            return ResponseEntity.status(429).body(Map.of("error", "Too many attempts. Please wait a few minutes."));
        }

        String token = payload.get("token");
        String password = payload.get("password");

        if (password == null || password.length() < 8 || password.length() > 72) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Password must contain between 8 and 72 characters."));
        }

        boolean success = passwordResetService.resetPassword(token, password);

        if (!success) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "This reset link is invalid or expired. Request a new one."));
        }

        return ResponseEntity.ok(Map.of("message", "Password changed successfully. You can now log in."));
    }
}
