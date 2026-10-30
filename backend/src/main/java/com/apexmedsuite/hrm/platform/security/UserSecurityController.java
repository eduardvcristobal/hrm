package com.apexmedsuite.hrm.platform.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/security/account")
public class UserSecurityController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final TransactionTemplate tx;

    public UserSecurityController(JdbcTemplate jdbc, PasswordEncoder passwords,
            org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.tx = new TransactionTemplate(manager);
    }

    public record ChangePassword(@NotBlank String currentPassword,
            @NotBlank @Size(min = 12, max = 72) String newPassword) {}
    public record Session(UUID id, Instant issuedAt, Instant lastUsedAt, Instant expiresAt, boolean current) {}

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal HrmUserPrincipal user,
            @Valid @RequestBody ChangePassword input) {
        if (input.newPassword().length() < 12 || input.newPassword().length() > 72
                || input.newPassword().chars().noneMatch(Character::isUpperCase)
                || input.newPassword().chars().noneMatch(Character::isLowerCase)
                || input.newPassword().chars().noneMatch(Character::isDigit)
                || input.newPassword().chars().noneMatch(ch -> !Character.isLetterOrDigit(ch))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Use at least 12 characters with uppercase, lowercase, a number, and a symbol.");
        }
        if (user.passwordHash() == null || !passwords.matches(input.currentPassword(), user.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect.");
        }
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE auth_identity SET password_hash=? WHERE id=?",
                    passwords.encode(input.newPassword()), user.id());
            jdbc.update("UPDATE auth_api_token SET revoked_at=now() WHERE identity_id=? AND revoked_at IS NULL", user.id());
        });
    }

    @GetMapping("/sessions")
    public List<Session> sessions(@AuthenticationPrincipal HrmUserPrincipal user,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String raw = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7).trim() : "";
        String currentHash = raw.isBlank() ? "" : ApiTokenService.hash(raw);
        return jdbc.query("""
                SELECT id, issued_at, last_used_at, expires_at, token_sha256
                FROM auth_api_token
                WHERE identity_id=? AND revoked_at IS NULL AND expires_at>now()
                ORDER BY issued_at DESC
                """, (rs, row) -> new Session(rs.getObject("id", UUID.class),
                        rs.getTimestamp("issued_at").toInstant(),
                        rs.getTimestamp("last_used_at") == null ? null : rs.getTimestamp("last_used_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(), currentHash.equals(rs.getString("token_sha256").trim())),
                user.id());
    }

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeSession(@AuthenticationPrincipal HrmUserPrincipal user, @PathVariable UUID sessionId) {
        int updated = jdbc.update("""
                UPDATE auth_api_token SET revoked_at=now()
                WHERE id=? AND identity_id=? AND revoked_at IS NULL
                """, sessionId, user.id());
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found.");
    }

    @DeleteMapping("/sessions/others")
    public java.util.Map<String, Integer> revokeOtherSessions(@AuthenticationPrincipal HrmUserPrincipal user,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String raw = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7).trim() : "";
        if (raw.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current session is required.");
        int revoked = jdbc.update("""
                UPDATE auth_api_token SET revoked_at=now()
                WHERE identity_id=? AND token_sha256<>? AND revoked_at IS NULL AND expires_at>now()
                """, user.id(), ApiTokenService.hash(raw));
        return java.util.Map.of("revoked", revoked);
    }
}
