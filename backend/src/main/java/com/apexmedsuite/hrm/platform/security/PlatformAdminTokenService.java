package com.apexmedsuite.hrm.platform.security;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlatformAdminTokenService {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final JdbcTemplate jdbc;
    private final String allowedEmails;

    public PlatformAdminTokenService(JdbcTemplate jdbc,
            @Value("${hrm.platform-admin.emails:}") String allowedEmails) {
        this.jdbc=jdbc;
        this.allowedEmails=allowedEmails;
    }

    public ApiTokenService.Token issue(String email) {
        String normalized=normalize(email);
        if(!isAllowlisted(normalized)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This Google account is not a platform administrator");
        }
        jdbc.update("INSERT INTO auth_platform_admin(email) VALUES(?) ON CONFLICT(email) DO NOTHING",normalized);
        List<UUID> admins=jdbc.query("SELECT id FROM auth_platform_admin WHERE email=? AND enabled=true",
            (rs,row)->rs.getObject("id",UUID.class),normalized);
        if(admins.size()!=1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Platform administrator access is disabled");
        }
        byte[] random=new byte[32];
        RANDOM.nextBytes(random);
        String value=HexFormat.of().formatHex(random);
        Instant expiresAt=Instant.now().plus(1,ChronoUnit.HOURS);
        jdbc.update("""
            INSERT INTO auth_platform_admin_token(admin_id,token_sha256,expires_at)
            VALUES(?,?,?)
            """,admins.get(0),ApiTokenService.hash(value),java.sql.Timestamp.from(expiresAt));
        return new ApiTokenService.Token(value,expiresAt);
    }

    public PlatformAdminPrincipal authenticate(String rawToken) {
        String hash=ApiTokenService.hash(rawToken);
        var matches=jdbc.query("""
            SELECT a.id,a.email,t.mfa_verified,
                   EXISTS(SELECT 1 FROM auth_platform_admin_totp_factor f WHERE f.admin_id=a.id AND f.enabled=true) AS mfa_enabled
            FROM auth_platform_admin_token t
            JOIN auth_platform_admin a ON a.id=t.admin_id
            WHERE t.token_sha256=? AND t.revoked_at IS NULL AND t.expires_at>now() AND a.enabled=true
            """,(rs,row)->new PlatformAdminPrincipal(rs.getObject("id",UUID.class),rs.getString("email"),rs.getBoolean("mfa_verified"),rs.getBoolean("mfa_enabled")),hash);
        if(matches.size()!=1) return null;
        PlatformAdminPrincipal admin=matches.get(0);
        if(!isAllowlisted(admin.email())) {
            revokeAll(admin.id());
            return null;
        }
        jdbc.update("UPDATE auth_platform_admin_token SET last_used_at=now() WHERE token_sha256=?",hash);
        return admin;
    }

    public void revoke(String rawToken,UUID adminId) {
        jdbc.update("""
            UPDATE auth_platform_admin_token SET revoked_at=now()
            WHERE token_sha256=? AND admin_id=? AND revoked_at IS NULL
            """,ApiTokenService.hash(rawToken),adminId);
    }

    private void revokeAll(UUID adminId) {
        jdbc.update("UPDATE auth_platform_admin_token SET revoked_at=now() WHERE admin_id=? AND revoked_at IS NULL",adminId);
    }

    private boolean isAllowlisted(String email) {
        if(allowedEmails==null || allowedEmails.isBlank()) return false;
        return java.util.Arrays.stream(allowedEmails.split(","))
            .map(String::trim).filter(value->!value.isEmpty())
            .map(PlatformAdminTokenService::normalize).anyMatch(email::equals);
    }

    private static String normalize(String email) {
        return email==null?"":email.trim().toLowerCase(Locale.ROOT);
    }
}
