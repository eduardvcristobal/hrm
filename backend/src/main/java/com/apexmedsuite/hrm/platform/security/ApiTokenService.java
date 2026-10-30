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
    public Token issue(HrmUserPrincipal principal,boolean mfaVerified) {
        byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);
        String raw=HexFormat.of().formatHex(bytes);
        Instant expiry=Instant.now().plusSeconds(3600);
        jdbc.update("INSERT INTO auth_api_token(identity_id,token_sha256,expires_at,mfa_verified) VALUES(?,?,?,?)",
            principal.id(),hash(raw),java.sql.Timestamp.from(expiry),mfaVerified);
        return new Token(raw,expiry);
    }
    public HrmUserPrincipal authenticate(String raw) {
        var rows=jdbc.query("""
            SELECT i.email,t.mfa_verified FROM auth_api_token t JOIN auth_identity i ON i.id=t.identity_id
            WHERE t.token_sha256=? AND t.revoked_at IS NULL AND t.expires_at>now() AND i.enabled
            """,(rs,n)->new TokenIdentity(rs.getString(1),rs.getBoolean(2)),hash(raw));
        if(rows.size()!=1) return null;
        var user=(HrmUserPrincipal)users.loadUserByUsername(rows.get(0).email());
        if(!user.active()) return null;
        if(!rows.get(0).mfaVerified()) return user;
        var authorities=new java.util.ArrayList<org.springframework.security.core.GrantedAuthority>(user.getAuthorities());
        authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MFA_VERIFIED"));
        return new HrmUserPrincipal(user.id(),user.tenantId(),user.email(),user.passwordHash(),user.active(),authorities);
    }
    private record TokenIdentity(String email,boolean mfaVerified) {}
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
