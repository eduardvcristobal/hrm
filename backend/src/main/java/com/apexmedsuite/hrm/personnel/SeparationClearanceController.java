package com.apexmedsuite.hrm.personnel;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/personnel/employees/{employeeId}/hr-cases/{caseId}/clearances")
public class SeparationClearanceController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public SeparationClearanceController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record Clearance(UUID id,String code,String label,boolean required,String status) {}
    public record NewClearance(@NotBlank @Pattern(regexp="[A-Z0-9_]+") @Size(max=40) String code,
        @NotBlank @Size(max=160) String label,boolean required) {}
    public record Review(@NotBlank @Pattern(regexp="CLEARED|BLOCKED") String status) {}
    @GetMapping
    public List<Clearance> list(@AuthenticationPrincipal HrmUserPrincipal user,
                  @PathVariable UUID employeeId,@PathVariable UUID caseId) {
        return tenants.inTenant(user.tenantId(),()->{
            ensureCase(user.tenantId(),employeeId,caseId,false);
            return jdbc.query("""
                SELECT id,clearance_code,label,required,status
                FROM personnel_separation_clearance WHERE tenant_id=? AND hr_case_id=?
                ORDER BY clearance_code LIMIT 100
                """,(rs,i)->map(rs),user.tenantId(),caseId);
        });
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Clearance add(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                         @PathVariable UUID caseId,@Valid @RequestBody NewClearance input) {
        return tenants.inTenant(user.tenantId(),()->{
            ensureCase(user.tenantId(),employeeId,caseId,true);
            Clearance entry=jdbc.queryForObject("""
                INSERT INTO personnel_separation_clearance(tenant_id,hr_case_id,clearance_code,label,required)
                VALUES(?,?,?,?,?) RETURNING id,clearance_code,label,required,status
                """,(rs,i)->map(rs),user.tenantId(),caseId,input.code(),input.label().trim(),input.required());
            audit(user,"SEPARATION_CLEARANCE_CREATED",entry.id());
            return entry;
        });
    }
    @PatchMapping("/{clearanceId}")
    public Clearance review(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
        @PathVariable UUID caseId,@PathVariable UUID clearanceId,@Valid @RequestBody Review input) {
        return tenants.inTenant(user.tenantId(),()->{
            ensureCase(user.tenantId(),employeeId,caseId,true);
            List<Clearance> changed=jdbc.query("""
                UPDATE personnel_separation_clearance
                SET status=?,reviewed_by=?,reviewed_at=now()
                WHERE tenant_id=? AND hr_case_id=? AND id=? AND status='PENDING'
                RETURNING id,clearance_code,label,required,status
                """,(rs,i)->map(rs),input.status(),user.id(),user.tenantId(),caseId,clearanceId);
            if(changed.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Clearance not pending");
            audit(user,"SEPARATION_CLEARANCE_"+input.status(),clearanceId);
            return changed.get(0);
        });
    }
    private void ensureCase(UUID tenant,UUID employee,UUID id,boolean pending) {
        Integer count=jdbc.queryForObject("""
            SELECT count(*) FROM personnel_hr_case
            WHERE tenant_id=? AND employee_id=? AND id=? AND case_type='SEPARATION'
            """+(pending?" AND status='PENDING'":""),Integer.class,tenant,employee,id);
        if(count==null||count==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Separation case not found");
    }
    private void audit(HrmUserPrincipal user,String action,UUID entityId) {
        jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,?,'personnel_separation_clearance',?)",
            user.tenantId(),user.id(),action,entityId);
    }
    private static Clearance map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Clearance(rs.getObject("id",UUID.class),rs.getString("clearance_code"),
           rs.getString("label"),rs.getBoolean("required"),rs.getString("status"));
    }
}
