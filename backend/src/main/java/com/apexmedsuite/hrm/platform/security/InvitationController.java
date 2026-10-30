package com.apexmedsuite.hrm.platform.security;

import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class InvitationController {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    private final PasswordEncoder passwords;
    private final GoogleIdTokenVerifier google;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    public InvitationController(JdbcTemplate jdbc,TenantJdbcExecutor tenants,PasswordEncoder passwords,GoogleIdTokenVerifier google,org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.tenants=tenants;this.passwords=passwords;
        this.google=google;
        this.tx=new org.springframework.transaction.support.TransactionTemplate(manager);
    }
    public record Invite(@NotBlank @Email @Size(max=254) String email,
                         @NotNull UserAdministrationController.Role role) {}
    public record InviteResult(UUID id, String email, String role, Instant expiresAt, String acceptanceUrl) {}
    public record PendingInvite(UUID id, String email, String role, Instant createdAt, Instant expiresAt) {}
    public record Accept(@NotBlank String token,
                         @NotBlank @Size(min=12,max=72) String password) {}
    public record GoogleAccept(@NotBlank String token,
                               @NotBlank @Size(max=8192) String idToken) {
        @Override public String toString() { return "GoogleAccept[token=[REDACTED], idToken=[REDACTED]]"; }
    }
    private static String digest(String value) {
        try {
            var bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    @PostMapping("/api/v1/admin/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteResult create(@AuthenticationPrincipal HrmUserPrincipal actor,@Valid @RequestBody Invite input) {
        if(input.role()==UserAdministrationController.Role.TENANT_ADMIN)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tenant admin invitations require separate owner authorization");
        byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes);
        String token=HexFormat.of().formatHex(bytes), hash=digest(token);
        Instant expiry=Instant.now().plusSeconds(48*3600);
        return tenants.inTenant(actor.tenantId(),()->{
            String email=input.email().trim().toLowerCase(Locale.ROOT);
            Integer existing=jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE email=?",
                Integer.class,email);
            if(existing!=null && existing>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Account already exists");
            UUID invitationId=UUID.randomUUID();
            jdbc.update("""
              INSERT INTO auth_invitation(id,tenant_id,email,role,token_sha256,expires_at,created_by)
              VALUES (?,?,?,?,?,?,?)
              """,invitationId,actor.tenantId(),email,input.role().name(),hash,java.sql.Timestamp.from(expiry),actor.id());
            jdbc.update("""
              INSERT INTO audit_event(tenant_id,actor_id,action,entity_type)
              VALUES (?,?,'INVITATION_CREATED','auth_invitation')
              """,actor.tenantId(),actor.id());
            return new InviteResult(invitationId,email,input.role().name(),expiry,
                "/invite/accept?token="+token);
        });
    }

    @GetMapping("/api/v1/admin/invitations")
    public java.util.List<PendingInvite> list(@AuthenticationPrincipal HrmUserPrincipal actor) {
        return tenants.inTenant(actor.tenantId(),()->jdbc.query("""
            SELECT id,email,role,created_at,expires_at FROM auth_invitation
            WHERE tenant_id=? AND used_at IS NULL AND revoked_at IS NULL AND expires_at>now()
            ORDER BY created_at DESC
            """,(rs,i)->new PendingInvite(rs.getObject("id",UUID.class),rs.getString("email"),
                rs.getString("role"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("expires_at").toInstant()),
            actor.tenantId()));
    }

    @DeleteMapping("/api/v1/admin/invitations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id,@AuthenticationPrincipal HrmUserPrincipal actor) {
        tenants.inTenant(actor.tenantId(),()->{
            int changed=jdbc.update("""
                UPDATE auth_invitation SET revoked_at=now()
                WHERE id=? AND tenant_id=? AND used_at IS NULL AND revoked_at IS NULL
                """,id,actor.tenantId());
            if(changed==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Invitation not found or already used");
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES (?,?,'INVITATION_REVOKED','auth_invitation',?)
                """,actor.tenantId(),actor.id(),id);
            return null;
        });
    }
    @PostMapping("/api/v1/invitations/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(@Valid @RequestBody Accept input) {
        tx.executeWithoutResult(status -> acceptInTransaction(input.token(),input.password(),null));
    }
    @PostMapping("/api/v1/invitations/accept/google")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acceptWithGoogle(@Valid @RequestBody GoogleAccept input) {
        String verifiedEmail=google.verifiedEmail(input.idToken());
        tx.executeWithoutResult(status -> acceptInTransaction(input.token(),randomPassword(),verifiedEmail));
    }
    private void acceptInTransaction(String token,String password,String verifiedGoogleEmail) {
        var invites=jdbc.query("""
           SELECT id,tenant_id,email,role FROM auth_invitation
           WHERE token_sha256=? AND used_at IS NULL AND revoked_at IS NULL AND expires_at>now()
           FOR UPDATE
           """,(rs,i)->new InviteRow(rs.getObject("id",UUID.class),
                rs.getObject("tenant_id",UUID.class),rs.getString("email"),rs.getString("role")),
                digest(token));
        if(invites.size()!=1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid or expired invitation");
        InviteRow invite=invites.get(0);
        if(verifiedGoogleEmail!=null && !invite.email().equalsIgnoreCase(verifiedGoogleEmail))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Sign in with the Google account invited to this workspace");
        Integer existing=jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE email=?",
            Integer.class,invite.email());
        if(existing!=null && existing>0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Account already exists");
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",String.class,invite.tenant().toString());
        UUID id=UUID.randomUUID();
        jdbc.update("""
           INSERT INTO auth_identity(id,tenant_id,email,password_hash,enabled)
           VALUES (?,?,?,?,true)
           """,id,invite.tenant(),invite.email(),passwords.encode(password));
        jdbc.update("INSERT INTO auth_identity_role(identity_id,role) VALUES (?,?)",id,invite.role());
        jdbc.update("UPDATE auth_invitation SET used_at=now() WHERE id=?",invite.id());
        jdbc.update("""
            INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
            VALUES (?,?,'INVITATION_ACCEPTED','auth_identity',?)
            """,invite.tenant(),id,id);
    }
    private String randomPassword() {
        byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes); return HexFormat.of().formatHex(bytes);
    }
    private record InviteRow(UUID id,UUID tenant,String email,String role) {}
}
