package com.apexmedsuite.hrm.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class ApiTokenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final DatabaseUserDetailsService users;
    public ApiTokenService(JdbcTemplate jdbc,DatabaseUserDetailsService users) {this.jdbc=jdbc;this.users=users;}
    public static String hash(String token) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(token.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    public record Token(String value,Instant expiresAt) {}
    public Token issue(HrmUserPrincipal principal) {
        byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);
        String raw=HexFormat.of().formatHex(bytes);
        Instant expiry=Instant.now().plusSeconds(3600);
        jdbc.update("INSERT INTO auth_api_token(identity_id,token_sha256,expires_at) VALUES(?,?,?)",
            principal.id(),hash(raw),java.sql.Timestamp.from(expiry));
        return new Token(raw,expiry);
    }
    public HrmUserPrincipal authenticate(String raw) {
        var rows=jdbc.query("""
            SELECT i.email FROM auth_api_token t JOIN auth_identity i ON i.id=t.identity_id
            WHERE t.token_sha256=? AND t.revoked_at IS NULL AND t.expires_at>now() AND i.enabled
            """,(rs,n)->rs.getString(1),hash(raw));
        if(rows.size()!=1) return null;
        var user=(HrmUserPrincipal)users.loadUserByUsername(rows.get(0));
        return user.active()?user:null;
    }
    public void revoke(String raw,HrmUserPrincipal actor) {
        jdbc.update("""
            UPDATE auth_api_token SET revoked_at=now()
            WHERE token_sha256=? AND identity_id=? AND revoked_at IS NULL
            """,hash(raw),actor.id());
    }
    public void revokeAll(UUID userId) {
        jdbc.update("UPDATE auth_api_token SET revoked_at=now() WHERE identity_id=? AND revoked_at IS NULL",userId);
    }
}
