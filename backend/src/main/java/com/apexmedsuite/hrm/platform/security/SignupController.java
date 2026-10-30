package com.apexmedsuite.hrm.platform.security;

import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionTemplate;

@RestController
public class SignupController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    private final PasswordEncoder passwords;
    private final DatabaseUserDetailsService users;
    private final ApiTokenService tokens;
    private final GoogleIdTokenVerifier google;
    private final TransactionTemplate transactions;
    private static final SecureRandom RANDOM = new SecureRandom();

    public SignupController(JdbcTemplate jdbc, TenantJdbcExecutor tenants, PasswordEncoder passwords,
            DatabaseUserDetailsService users, ApiTokenService tokens,
            GoogleIdTokenVerifier google, org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.passwords = passwords;
        this.users = users;
        this.tokens = tokens;
        this.google = google;
        this.transactions = new TransactionTemplate(manager);
    }

    public record Signup(
            @NotBlank @Size(max = 200) String workspaceName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{3,32}") String username,
            @NotBlank @Size(min = 12, max = 72) String password) {
        @Override public String toString() {
            return "Signup[workspaceName=" + workspaceName + ", email=" + email
                + ", username=" + username + ", password=[REDACTED]]";
        }
    }

    public record GoogleSignup(
            @NotBlank @Size(max = 200) String workspaceName,
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{3,32}") String username,
            @NotBlank @Size(max = 8192) String idToken) {
        @Override public String toString() { return "GoogleSignup[workspaceName=" + workspaceName
            + ", username=" + username + ", idToken=[REDACTED]]"; }
    }

    @PostMapping("/api/v1/security/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiTokenService.Token signup(@Valid @RequestBody Signup input) {
        try {
            return transactions.execute(status -> createWorkspace(input.workspaceName(), input.email(),
                input.username(), input.password()));
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "That email or username is already registered.");
        }
    }

    @PostMapping("/api/v1/security/signup/google")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiTokenService.Token signupWithGoogle(@Valid @RequestBody GoogleSignup input) {
        try {
            String email = google.verifiedEmail(input.idToken());
            return transactions.execute(status -> createWorkspace(input.workspaceName(), email,
                input.username(), randomPassword()));
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "That email or username is already registered.");
        }
    }

    private ApiTokenService.Token createWorkspace(String workspaceName, String emailInput,
            String usernameInput, String password) {
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at most 72 UTF-8 bytes.");
        }
        String email = emailInput.trim().toLowerCase(Locale.ROOT);
        String username = usernameInput.trim().toLowerCase(Locale.ROOT);
        String workspace = workspaceName.trim();
        if (workspace.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Workspace name is required.");

        UUID tenantId = UUID.randomUUID();
        String slug = "workspace-" + tenantId.toString().substring(0, 12);
        jdbc.update("INSERT INTO tenant(id,slug,display_name) VALUES (?,?,?)", tenantId, slug, workspace);
        UUID identityId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO auth_identity(id,tenant_id,email,username,password_hash,enabled)
            VALUES (?,?,?,?,?,true)
            """, identityId, tenantId, email, username, passwords.encode(password));
        jdbc.update("INSERT INTO auth_identity_role(identity_id,role) VALUES (?, 'TENANT_ADMIN')", identityId);
        tenants.inTenant(tenantId, () -> {
            jdbc.update("INSERT INTO company(tenant_id,company_code,legal_name) VALUES (?,'HQ',?)",
                tenantId, workspace);
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES (?,?,'WORKSPACE_SIGNUP','auth_identity',?)
                """, tenantId, identityId, identityId);
            return null;
        });

        HrmUserPrincipal principal = (HrmUserPrincipal) users.loadUserByUsername(email);
        // Bootstrap token is intentionally not MFA-verified. The first owner must enroll MFA
        // before using tenant-administration endpoints.
        return tokens.issue(principal, false);
    }

    private String randomPassword() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
