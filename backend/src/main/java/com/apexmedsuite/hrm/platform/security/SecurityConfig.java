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
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.core.Authentication;

@Configuration
public class SecurityConfig {
    private static final AuthorizationManager<RequestAuthorizationContext> PLATFORM_ADMIN_MFA = (authentication, context) -> {
        Authentication current=authentication.get();
        Object principal=current==null?null:current.getPrincipal();
        return new AuthorizationDecision(principal instanceof PlatformAdminPrincipal admin
            && (!admin.mfaEnabled() || admin.mfaVerified()));
    };
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, JdbcTemplate jdbc, ApiTokenService tokens,
            PlatformAdminTokenService platformAdmins, DatabaseUserDetailsService users, PasswordEncoder encoder) throws Exception {
        return http
            .addFilterBefore(new LoginThrottleFilter(jdbc, users, encoder), BasicAuthenticationFilter.class)
            .addFilterBefore(new BearerTokenFilter(tokens, platformAdmins), BasicAuthenticationFilter.class)
            .csrf(csrf -> csrf.ignoringRequestMatchers(request -> {
                String header = request.getHeader("Authorization");
                return (header != null && header.startsWith("Bearer ")
                    && request.getRequestURI().startsWith("/api/v1/")
                    || (request.getMethod().equals("POST")
                    && (request.getRequestURI().equals("/api/v1/security/platform-admin/google")
                    || request.getRequestURI().equals("/api/v1/security/google")
                    || request.getRequestURI().equals("/api/v1/security/signup/google"))));
            }))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .httpBasic(basic -> {})
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/me").authenticated()
                .requestMatchers("/api/v1/security/tokens", "/api/v1/security/tokens/**")
                    .hasAnyAuthority("ROLE_TENANT_ADMIN","ROLE_HR_ADMIN","ROLE_PAYROLL_OFFICER","ROLE_MANAGER","ROLE_EMPLOYEE","ROLE_AUDITOR")
                .requestMatchers("/api/v1/security/mfa", "/api/v1/security/mfa/**")
                    .hasAnyAuthority("ROLE_TENANT_ADMIN","ROLE_HR_ADMIN","ROLE_PAYROLL_OFFICER","ROLE_MANAGER","ROLE_EMPLOYEE","ROLE_AUDITOR")
                .requestMatchers("/api/v1/security/account/**")
                    .hasAnyAuthority("ROLE_TENANT_ADMIN","ROLE_HR_ADMIN","ROLE_PAYROLL_OFFICER","ROLE_MANAGER","ROLE_EMPLOYEE","ROLE_AUDITOR")
                .requestMatchers(HttpMethod.GET, "/api/v1/security/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/security/password-reset/request").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/security/password-reset/confirm").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/invitations/accept").permitAll()
                .requestMatchers("/api/v1/admin/invitations", "/api/v1/admin/invitations/**").hasAllAuthorities("ROLE_TENANT_ADMIN","ROLE_MFA_VERIFIED")
                .requestMatchers("/api/v1/admin/users", "/api/v1/admin/users/**").hasAllAuthorities("ROLE_TENANT_ADMIN","ROLE_MFA_VERIFIED")
                .requestMatchers("/api/v1/companies", "/api/v1/companies/**").hasAllAuthorities("ROLE_TENANT_ADMIN","ROLE_MFA_VERIFIED")
                .requestMatchers("/api/v1/timekeeping/**")
                    .hasAllAuthorities("ROLE_TENANT_ADMIN","ROLE_MFA_VERIFIED")
                .requestMatchers("/api/v1/personnel/employees/*/hr-cases", "/api/v1/personnel/employees/*/hr-cases/**",
                    "/api/v1/personnel/employees/*/documents", "/api/v1/personnel/employees/*/documents/**")
                    .hasAllAuthorities("ROLE_TENANT_ADMIN","ROLE_MFA_VERIFIED")
                .requestMatchers("/api/v1/personnel/rehire-policy")
                    .hasAllAuthorities("ROLE_TENANT_ADMIN","ROLE_MFA_VERIFIED")
                .requestMatchers("/api/v1/personnel/employees/*/rehire-requests", "/api/v1/personnel/employees/*/rehire-requests/**")
                    .hasAllAuthorities("ROLE_TENANT_ADMIN","ROLE_MFA_VERIFIED")
                .requestMatchers("/api/v1/personnel/employees/*/private")
                    .hasAllAuthorities("ROLE_TENANT_ADMIN","ROLE_MFA_VERIFIED")
                .requestMatchers("/api/v1/personnel/employees/*/compensation", "/api/v1/personnel/employees/*/compensation/**")
                    .access((authentication,context) -> new org.springframework.security.authorization.AuthorizationDecision(
                        PersonnelAccessPolicy.compensation(authentication.get().getAuthorities())))
                .requestMatchers("/api/v1/personnel/**")
                    .access((authentication,context) -> new org.springframework.security.authorization.AuthorizationDecision(
                        PersonnelAccessPolicy.personnel(authentication.get().getAuthorities())))
                .requestMatchers(HttpMethod.POST, "/api/v1/security/signup").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/security/signup/google").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/invitations/accept", "/api/v1/invitations/accept/google").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/security/platform-admin/google").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/security/google").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/api/v1/security/platform-admin/tokens/current").hasAuthority("ROLE_PLATFORM_ADMIN")
                .requestMatchers("/api/v1/platform-admin/mfa", "/api/v1/platform-admin/mfa/**").hasAuthority("ROLE_PLATFORM_ADMIN")
                // The backup controller performs both platform-admin and MFA checks against the
                // injected principal. Keep this route out of the generic matcher chain so the
                // same principal used by the MFA status endpoint governs the operation.
                .requestMatchers((RequestMatcher) request -> HttpMethod.POST.matches(request.getMethod())
                    && ("/api/v1/platform-admin/backup".equals(request.getServletPath())
                        || "/api/v1/platform-admin/backup/".equals(request.getServletPath())))
                    .permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/platform-admin/**").access(PLATFORM_ADMIN_MFA)
                .requestMatchers(HttpMethod.PUT, "/api/v1/platform-admin/**").access(PLATFORM_ADMIN_MFA)
                .requestMatchers(HttpMethod.PATCH, "/api/v1/platform-admin/**").access(PLATFORM_ADMIN_MFA)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/platform-admin/**").access(PLATFORM_ADMIN_MFA)
                .requestMatchers("/api/v1/platform-admin/**").hasAuthority("ROLE_PLATFORM_ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/billing/subscription")
                    .hasAnyAuthority("ROLE_TENANT_ADMIN","ROLE_HR_ADMIN","ROLE_PAYROLL_OFFICER","ROLE_MANAGER","ROLE_EMPLOYEE","ROLE_AUDITOR")
                .requestMatchers(HttpMethod.POST, "/api/v1/billing/payment-requests")
                    .hasAuthority("ROLE_TENANT_ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/audit-logs")
                    .hasAnyAuthority("ROLE_TENANT_ADMIN","ROLE_HR_ADMIN","ROLE_AUDITOR")
                .requestMatchers("/api/v1/admin/invitations", "/api/v1/admin/invitations/**").hasAnyAuthority("ROLE_TENANT_ADMIN","ROLE_HR_ADMIN")
                .requestMatchers("/api/v1/admin/users", "/api/v1/admin/users/**").hasAnyAuthority("ROLE_TENANT_ADMIN","ROLE_HR_ADMIN")
                .requestMatchers("/api/v1/companies", "/api/v1/companies/**").hasAuthority("ROLE_TENANT_ADMIN")
                .anyRequest().denyAll())
            .build();
    }
}
