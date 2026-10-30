package com.apexmedsuite.hrm.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, JdbcTemplate jdbc, ApiTokenService tokens, DatabaseUserDetailsService users, PasswordEncoder encoder) throws Exception {
        return http
            .addFilterBefore(new LoginThrottleFilter(jdbc, users, encoder), BasicAuthenticationFilter.class)
            .addFilterBefore(new BearerTokenFilter(tokens), BasicAuthenticationFilter.class)
            .csrf(csrf -> csrf.ignoringRequestMatchers(request -> {
                String header = request.getHeader("Authorization");
                return header != null && header.startsWith("Bearer ")
                    && request.getRequestURI().startsWith("/api/v1/");
            }))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .httpBasic(basic -> {})
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/me").authenticated()
                .requestMatchers("/api/v1/security/tokens", "/api/v1/security/tokens/**").authenticated()
                .requestMatchers("/api/v1/security/mfa", "/api/v1/security/mfa/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/security/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/security/password-reset/request").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/security/password-reset/confirm").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/invitations/accept").permitAll()
                .requestMatchers("/api/v1/admin/invitations", "/api/v1/admin/invitations/**").hasAllAuthorities("ROLE_TENANT_ADMIN","ROLE_MFA_VERIFIED")
                .requestMatchers("/api/v1/admin/users", "/api/v1/admin/users/**").hasAllAuthorities("ROLE_TENANT_ADMIN","ROLE_MFA_VERIFIED")
                .requestMatchers("/api/v1/companies", "/api/v1/companies/**").hasAllAuthorities("ROLE_TENANT_ADMIN","ROLE_MFA_VERIFIED")
                .requestMatchers("/api/v1/personnel/**").hasAllAuthorities("ROLE_TENANT_ADMIN","ROLE_MFA_VERIFIED")
                .anyRequest().denyAll())
            .build();
    }
}
