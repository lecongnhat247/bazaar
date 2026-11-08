package com.bazaar.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.security.oauth2.jwt.JwtDecoder;

final class RateLimitIdentity {

    private RateLimitIdentity() {
    }

    static String tokenHash(JwtDecoder decoder, String authorization, String remoteAddress) {
        String subject = subject(decoder, authorization);
        String material = subject == null
                ? "ip:" + (remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress)
                : "sub:" + subject;
        return digest(material);
    }

    private static String subject(JwtDecoder decoder, String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            return null;
        }
        try {
            String subject = decoder.decode(token).getSubject();
            if (subject == null || subject.isBlank()) {
                return null;
            }
            return subject;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String digest(String material) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append("%02x".formatted(hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
