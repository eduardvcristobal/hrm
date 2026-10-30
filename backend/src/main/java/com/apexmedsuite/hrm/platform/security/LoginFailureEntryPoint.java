package com.apexmedsuite.hrm.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/** Record failures in the HTTP Basic failure handler (not after the filter chain). */
public class LoginFailureEntryPoint implements AuthenticationEntryPoint {
    private final JdbcTemplate jdbc;
    public LoginFailureEntryPoint(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public void commence(HttpServletRequest request,HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        String key=LoginThrottleFilter.key(request);
        if(key!=null) {
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
        }
        response.setHeader("WWW-Authenticate","Basic realm=\"HRM\"");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
