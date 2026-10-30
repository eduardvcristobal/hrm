package com.apexmedsuite.hrm.personnel;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/personnel/rehire-policy")
public class RehirePolicyController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public RehirePolicyController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record Policy(boolean enabled,int minimumWaitDays) {}
    public record Change(boolean enabled,@Min(0) @Max(3650) int minimumWaitDays) {}
    @GetMapping
    public Policy get(@AuthenticationPrincipal HrmUserPrincipal user) {
        return tenants.inTenant(user.tenantId(),()->{
            List<Policy> rows=jdbc.query("""
                SELECT enabled,minimum_wait_days FROM personnel_rehire_policy WHERE tenant_id=?
                """,(rs,i)->new Policy(rs.getBoolean(1),rs.getInt(2)),user.tenantId());
            return rows.isEmpty()?new Policy(true,0):rows.get(0);
        });
    }
    @PutMapping
    public Policy update(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody Change input) {
        return tenants.inTenant(user.tenantId(),()->{
            jdbc.update("""
                INSERT INTO personnel_rehire_policy(tenant_id,enabled,minimum_wait_days,updated_by)
                VALUES(?,?,?,?)
                ON CONFLICT(tenant_id) DO UPDATE
                SET enabled=EXCLUDED.enabled,minimum_wait_days=EXCLUDED.minimum_wait_days,
                    updated_by=EXCLUDED.updated_by,updated_at=now()
                """,user.tenantId(),input.enabled(),input.minimumWaitDays(),user.id());
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'REHIRE_POLICY_CHANGED','personnel_rehire_policy',?)
                """,user.tenantId(),user.id(),user.tenantId());
            return new Policy(input.enabled(),input.minimumWaitDays());
        });
    }
}
