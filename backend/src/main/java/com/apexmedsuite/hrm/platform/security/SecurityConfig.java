package com.apexmedsuite.hrm.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(basic -> {})
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/me").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/invitations/accept").permitAll()
                .requestMatchers("/api/v1/admin/invitations", "/api/v1/admin/invitations/**").hasRole("TENANT_ADMIN")
                .requestMatchers("/api/v1/admin/users", "/api/v1/admin/users/**").hasRole("TENANT_ADMIN")
                .requestMatchers("/api/v1/companies", "/api/v1/companies/**").hasRole("TENANT_ADMIN")
                .anyRequest().denyAll())
            .build();
    }
}
