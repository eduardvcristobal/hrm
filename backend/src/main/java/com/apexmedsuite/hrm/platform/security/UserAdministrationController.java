package com.apexmedsuite.hrm.platform.security;

import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/users")
public class UserAdministrationController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public UserAdministrationController(JdbcTemplate jdbc, TenantJdbcExecutor tenants) {
        this.jdbc=jdbc; this.tenants=tenants;
    }
    public record Account(UUID id, String email, boolean enabled, List<String> roles) {}
    public enum Role { TENANT_ADMIN, HR_ADMIN, PAYROLL_OFFICER, MANAGER, EMPLOYEE, AUDITOR }
    public record RoleChange(@NotNull Role role) {}
    public record StatusChange(@NotNull Boolean enabled) {}

    @GetMapping
    public List<Account> list(@AuthenticationPrincipal HrmUserPrincipal actor) {
        return tenants.inTenant(actor.tenantId(), () -> jdbc.query("""
            SELECT id,email,enabled FROM auth_identity WHERE tenant_id=? ORDER BY email
            """,(rs,i)-> new Account(rs.getObject("id",UUID.class),rs.getString("email"),
                rs.getBoolean("enabled"),jdbc.query("""
                    SELECT role FROM auth_identity_role WHERE identity_id=? ORDER BY role
                    """,(rr,j)->rr.getString(1),rs.getObject("id",UUID.class))),actor.tenantId()));
    }

    @PutMapping("/{id}/roles/{role}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addRole(@PathVariable UUID id,@PathVariable Role role,
            @AuthenticationPrincipal HrmUserPrincipal actor) {
        tenants.inTenant(actor.tenantId(),()->{
            requireUser(actor,id);
            jdbc.update("""
                INSERT INTO auth_identity_role(identity_id,role) VALUES (?,?)
                ON CONFLICT DO NOTHING
                """,id,role.name());
            audit(actor,id,"USER_ROLE_ADDED");
            return null;
        });
    }

    @DeleteMapping("/{id}/roles/{role}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeRole(@PathVariable UUID id,@PathVariable Role role,
            @AuthenticationPrincipal HrmUserPrincipal actor) {
        tenants.inTenant(actor.tenantId(),()->{
            requireUser(actor,id);
            if (role == Role.TENANT_ADMIN) guardLastAdmin(actor,id);
            jdbc.update("DELETE FROM auth_identity_role WHERE identity_id=? AND role=?",id,role.name());
            audit(actor,id,"USER_ROLE_REMOVED");
            return null;
        });
    }

    @PatchMapping("/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeStatus(@PathVariable UUID id,@Valid @RequestBody StatusChange input,
            @AuthenticationPrincipal HrmUserPrincipal actor) {
        tenants.inTenant(actor.tenantId(),()->{
            requireUser(actor,id);
            if (!input.enabled()) guardLastAdmin(actor,id);
            jdbc.update("UPDATE auth_identity SET enabled=? WHERE tenant_id=? AND id=?",
                input.enabled(),actor.tenantId(),id);
            audit(actor,id,"USER_STATUS_CHANGED");
            return null;
        });
    }

    private void requireUser(HrmUserPrincipal actor, UUID id) {
        Integer n=jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE tenant_id=? AND id=?",
            Integer.class,actor.tenantId(),id);
        if(n==null || n==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    private void guardLastAdmin(HrmUserPrincipal actor, UUID id) {
        // Forbid self-removal and self-deactivation; count active admins in the tenant.
        if(actor.id().equals(id)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Cannot remove own admin access");
        Integer admin=jdbc.queryForObject("""
            SELECT count(*) FROM auth_identity a JOIN auth_identity_role r ON r.identity_id=a.id
            WHERE a.tenant_id=? AND a.enabled AND r.role='TENANT_ADMIN'
            """,Integer.class,actor.tenantId());
        if(admin!=null && admin<=1) {
            Integer target=jdbc.queryForObject("""
              SELECT count(*) FROM auth_identity_role WHERE identity_id=? AND role='TENANT_ADMIN'
              """,Integer.class,id);
            if(target!=null && target>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Last tenant admin");
        }
    }
    private void audit(HrmUserPrincipal actor,UUID id,String action) {
        jdbc.update("""
            INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
            VALUES(?,?,?,'auth_identity',?)
            """,actor.tenantId(),actor.id(),action,id);
    }
}
