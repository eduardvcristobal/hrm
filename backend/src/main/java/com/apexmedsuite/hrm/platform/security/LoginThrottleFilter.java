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
    public LoginThrottleFilter(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String key=credentialKey(request);
        if(key==null) {chain.doFilter(request,response);return;}
        Integer attempts=jdbc.queryForObject("""
            SELECT COALESCE((SELECT attempts FROM auth_login_attempt
              WHERE key_sha256=? AND window_start>now()-interval '10 minutes'),0)
            """,Integer.class,key);
        if(attempts!=null && attempts>=10) {
            response.setHeader("Retry-After","600");
            response.sendError(429,"Too many unsuccessful authentication attempts");
            return;
        }
        chain.doFilter(request,response);
        if(response.getStatus()==HttpServletResponse.SC_UNAUTHORIZED) {
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
        } else if(response.getStatus()<400) {
            jdbc.update("DELETE FROM auth_login_attempt WHERE key_sha256=?",key);
        }
    }
    private String credentialKey(HttpServletRequest request) {
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
