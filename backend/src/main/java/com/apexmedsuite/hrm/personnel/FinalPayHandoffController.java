package com.apexmedsuite.hrm.personnel;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** An auditable, non-financial handoff. Payroll owns calculation, settlement and disbursement. */
@RestController
@RequestMapping("/api/v1/personnel/employees/{employeeId}/hr-cases/{caseId}/final-pay")
public class FinalPayHandoffController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public FinalPayHandoffController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record Handoff(UUID id,UUID employeeId,UUID caseId,String status,UUID createdBy,UUID acknowledgedBy) {}
    @GetMapping
    public List<Handoff> list(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                              @PathVariable UUID caseId) {
        return tenants.inTenant(user.tenantId(),()->jdbc.query("""
            SELECT id,employee_id,hr_case_id,status,created_by,acknowledged_by
            FROM personnel_final_pay_handoff WHERE tenant_id=? AND employee_id=? AND hr_case_id=?
            """,(rs,n)->map(rs),user.tenantId(),employeeId,caseId));
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Handoff request(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                           @PathVariable UUID caseId) {
        return tenants.inTenant(user.tenantId(),()->{
            List<String> cases=jdbc.query("""
                SELECT status FROM personnel_hr_case WHERE tenant_id=? AND employee_id=?
                AND id=? AND case_type='SEPARATION' FOR UPDATE
                """,(rs,n)->rs.getString(1),user.tenantId(),employeeId,caseId);
            if(cases.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Separation case not found");
            if(!cases.get(0).equals("APPROVED"))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Final-pay handoff requires approved separation");
            Handoff result=jdbc.queryForObject("""
                INSERT INTO personnel_final_pay_handoff(tenant_id,employee_id,hr_case_id,created_by)
                VALUES(?,?,?,?)
                ON CONFLICT(tenant_id,hr_case_id) DO UPDATE SET hr_case_id=EXCLUDED.hr_case_id
                RETURNING id,employee_id,hr_case_id,status,created_by,acknowledged_by
                """,(rs,n)->map(rs),user.tenantId(),employeeId,caseId,user.id());
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'FINAL_PAY_HANDOFF_REQUESTED','personnel_final_pay_handoff',?)
                """,user.tenantId(),user.id(),result.id());
            return result;
        });
    }
    private static Handoff map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Handoff(rs.getObject("id",UUID.class),rs.getObject("employee_id",UUID.class),
            rs.getObject("hr_case_id",UUID.class),rs.getString("status"),
            rs.getObject("created_by",UUID.class),rs.getObject("acknowledged_by",UUID.class));
    }
}
