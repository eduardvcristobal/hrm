package com.apexmedsuite.hrm.platform.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GoogleIdTokenVerifier {
    private final String clientId;
    private final JwtDecoder decoder;

    public GoogleIdTokenVerifier(
            @Value("${hrm.google.client-id:}") String clientId,
            @Value("${hrm.google.jwk-set-uri:https://www.googleapis.com/oauth2/v3/certs}") String jwkSetUri) {
        this.clientId=clientId;
        this.decoder=NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    }

    public String verifiedEmail(String idToken) {
        if(clientId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Google login is not configured");
        }
        try {
            Jwt jwt=decoder.decode(idToken);
            String issuer=String.valueOf(jwt.getIssuer());
            Instant now=Instant.now();
            Object emailVerified=jwt.getClaims().get("email_verified");
            boolean verified=Boolean.TRUE.equals(emailVerified)
                || "true".equalsIgnoreCase(String.valueOf(emailVerified));
            if(!(issuer.equals("https://accounts.google.com") || issuer.equals("accounts.google.com"))
                    || !jwt.getAudience().contains(clientId)
                    || !verified
                    || jwt.getExpiresAt()==null || !jwt.getExpiresAt().isAfter(now)
                    || jwt.getIssuedAt()==null || jwt.getIssuedAt().isBefore(now.minus(10,ChronoUnit.MINUTES))
                    || jwt.getIssuedAt().isAfter(now.plus(60,ChronoUnit.SECONDS))) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Google identity");
            }
            String email=jwt.getClaimAsString("email");
            if(email==null || email.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Google identity");
            }
            return email.trim().toLowerCase(Locale.ROOT);
        } catch(JwtException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Google identity");
        }
    }
}
