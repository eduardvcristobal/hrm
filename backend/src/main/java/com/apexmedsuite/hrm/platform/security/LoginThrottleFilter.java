package com.apexmedsuite.hrm.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/** Shared PostgreSQL login-attempt counter. Client-supplied forwarded headers are intentionally ignored. */
public class LoginThrottleFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    private final DatabaseUserDetailsService users;
    private final org.springframework.security.crypto.password.PasswordEncoder passwords;
    public LoginThrottleFilter(JdbcTemplate jdbc,DatabaseUserDetailsService users,
         org.springframework.security.crypto.password.PasswordEncoder passwords) {
        this.jdbc=jdbc;this.users=users;this.passwords=passwords;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String key=key(request);
        if(key==null) {chain.doFilter(request,response);return;}
        Integer attempts=jdbc.queryForObject("""
            SELECT COALESCE((SELECT attempts FROM auth_login_attempt
              WHERE key_sha256=? AND window_start>now()-interval '10 minutes'),0)
            """,Integer.class,key);
        if(attempts!=null && attempts>=10) {
            response.setHeader("Retry-After","600");
            response.setStatus(429);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"title\":\"Too Many Requests\",\"status\":429}");
            return;
        }
        boolean valid = validCredentials(request);
        if(valid) {
            jdbc.update("DELETE FROM auth_login_attempt WHERE key_sha256=?",key);
            chain.doFilter(request,response);
            return;
        }
        // Count only invalid credentials, not valid credentials rejected by MFA/authorization.
        // Count attempted Basic authentication before invoking Spring Security:
        // failed authentication may exit the chain by an exception path.
        jdbc.update("""
            INSERT INTO auth_login_attempt(key_sha256,attempts,window_start,updated_at)
            VALUES (?,1,now(),now())
            ON CONFLICT(key_sha256) DO UPDATE
            SET attempts=CASE WHEN auth_login_attempt.window_start<now()-interval '10 minutes'
                 THEN 1 ELSE auth_login_attempt.attempts+1 END,
                window_start=CASE WHEN auth_login_attempt.window_start<now()-interval '10 minutes'
                 THEN now() ELSE auth_login_attempt.window_start END,
                updated_at=now()
            """,key);
        chain.doFilter(request,response);

    }
    private boolean validCredentials(HttpServletRequest request) {
        String auth=request.getHeader("Authorization");
        try {
            String decoded=new String(Base64.getDecoder().decode(auth.substring(6)),StandardCharsets.UTF_8);
            int separator=decoded.indexOf(':');
            if(separator<1) return false;
            String username=decoded.substring(0,separator).toLowerCase(Locale.ROOT);
            String password=decoded.substring(separator+1);
            var user=users.loadUserByUsername(username);
            return user.isEnabled() && passwords.matches(password,user.getPassword());
        } catch(Exception ex) {return false;}
    }
    static String key(HttpServletRequest request) {
        if("POST".equals(request.getMethod())
                && ("/api/v1/security/platform-admin/google".equals(request.getRequestURI())
                || "/api/v1/security/google".equals(request.getRequestURI())
                || "/api/v1/security/signup".equals(request.getRequestURI())
                || "/api/v1/security/signup/google".equals(request.getRequestURI()))) {
            try {
                var digest=MessageDigest.getInstance("SHA-256").digest(
                    (request.getRemoteAddr()+"|"+request.getRequestURI()).getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest);
            } catch(Exception ex) {return null;}
        }
        String header=request.getHeader("Authorization");
        if(header==null || !header.regionMatches(true,0,"Basic ",0,6) || header.length()>1024) return null;
        try {
            var raw=new String(Base64.getDecoder().decode(header.substring(6)),StandardCharsets.UTF_8);
            int colon=raw.indexOf(':');
            if(colon<1 || colon>254) return null;
            String user=raw.substring(0,colon).toLowerCase(Locale.ROOT);
            var digest=MessageDigest.getInstance("SHA-256").digest(
                (request.getRemoteAddr()+"|"+user).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch(Exception ex) {return null;}
    }
}
