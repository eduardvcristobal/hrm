package com.apexmedsuite.hrm.personnel;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** HR case submission, independent decision and audit; separation does not mutate payroll. */
@RestController
@RequestMapping("/api/v1/personnel/employees/{employeeId}/hr-cases")
public class HrCaseController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public HrCaseController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record Case(UUID id,String type,String status,LocalDate effectiveDate,UUID requestedBy,UUID decidedBy) {}
    public record Request(@NotBlank @Pattern(regexp="ONBOARDING|SEPARATION") String type,
        @NotNull LocalDate effectiveDate,@NotBlank @Size(max=500) String reason) {}
    @GetMapping
    public List<Case> list(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId) {
        return tenants.inTenant(user.tenantId(),()->{
            employee(user.tenantId(),employeeId,false);
            return jdbc.query("""
                SELECT id,case_type,status,effective_date,requested_by,decided_by
                FROM personnel_hr_case WHERE tenant_id=? AND employee_id=? ORDER BY created_at DESC LIMIT 100
                """,(rs,i)->map(rs),user.tenantId(),employeeId);
        });
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Case create(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
        @Valid @RequestBody Request input) {
        return tenants.inTenant(user.tenantId(),()->{
            String status=employee(user.tenantId(),employeeId,true);
            if(input.type().equals("SEPARATION") && (status.equals("TERMINATED")||status.equals("RETIRED")))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Employee already separated");
            if(input.type().equals("ONBOARDING") && !status.equals("ACTIVE"))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Onboarding requires an active employee");
            Case result=jdbc.queryForObject("""
                INSERT INTO personnel_hr_case(tenant_id,employee_id,case_type,effective_date,reason,requested_by)
                VALUES(?,?,?,?,?,?) RETURNING id,case_type,status,effective_date,requested_by,decided_by
                """,(rs,i)->map(rs),user.tenantId(),employeeId,input.type(),input.effectiveDate(),
                  input.reason().trim(),user.id());
            audit(user,"HR_CASE_REQUESTED",result.id());
            return result;
        });
    }
    @PostMapping("/{caseId}/decision")
    public Case decide(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
        @PathVariable UUID caseId,@RequestParam @Pattern(regexp="APPROVED|REJECTED") String decision) {
        return tenants.inTenant(user.tenantId(),()->{
            String status=employee(user.tenantId(),employeeId,true);
            List<Pending> pending=jdbc.query("""
                SELECT case_type,effective_date,requested_by FROM personnel_hr_case
                WHERE tenant_id=? AND employee_id=? AND id=? AND status='PENDING' FOR UPDATE
                """,(rs,i)->new Pending(rs.getString(1),rs.getObject(2,LocalDate.class),
                    rs.getObject(3,UUID.class)),user.tenantId(),employeeId,caseId);
            if(pending.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Pending case not found");
            Pending p=pending.get(0);
            if(p.requestedBy().equals(user.id())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Independent decision required");
            if(decision.equals("APPROVED") && p.type().equals("ONBOARDING")) {
                Integer count=jdbc.queryForObject("""
                    SELECT count(*) FROM personnel_onboarding_requirement
                    WHERE tenant_id=? AND hr_case_id=? AND mandatory
                    """,Integer.class,user.tenantId(),caseId);
                Integer incomplete=jdbc.queryForObject("""
                    SELECT count(*) FROM personnel_onboarding_requirement
                    WHERE tenant_id=? AND hr_case_id=? AND mandatory AND status<>'VERIFIED'
                    """,Integer.class,user.tenantId(),caseId);
                if(count==null||count==0||incomplete!=null&&incomplete>0)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Mandatory onboarding requirements not verified");
            }
            if(decision.equals("APPROVED") && p.type().equals("SEPARATION")) {
                Integer required=jdbc.queryForObject("""
                    SELECT count(*) FROM personnel_separation_clearance
                    WHERE tenant_id=? AND hr_case_id=? AND required
                    """,Integer.class,user.tenantId(),caseId);
                Integer incomplete=jdbc.queryForObject("""
                    SELECT count(*) FROM personnel_separation_clearance
                    WHERE tenant_id=? AND hr_case_id=? AND required AND status<>'CLEARED'
                    """,Integer.class,user.tenantId(),caseId);
                if(required==null||required==0||incomplete!=null&&incomplete>0)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Required exit clearances are incomplete");

                if(status.equals("TERMINATED")||status.equals("RETIRED"))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Employee already separated");
                LocalDate latest=jdbc.queryForObject("""
                    SELECT max(effective_date) FROM personnel_employment_history WHERE tenant_id=? AND employee_id=?
                    """,LocalDate.class,user.tenantId(),employeeId);
                if(latest!=null&&!p.date().isAfter(latest))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Effective date must follow employment history");
                if(p.date().isAfter(LocalDate.now()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Future separation needs scheduled execution");
                jdbc.update("""
                    UPDATE personnel_employee SET employment_status='TERMINATED',updated_at=now()
                    WHERE tenant_id=? AND id=?
                    """,user.tenantId(),employeeId);
                jdbc.update("""
                    INSERT INTO personnel_employment_history
                     (tenant_id,employee_id,effective_date,employment_type,employment_status,reason)
                    SELECT tenant_id,id,?,employment_type,'TERMINATED','APPROVED_SEPARATION'
                    FROM personnel_employee WHERE tenant_id=? AND id=?
                    """,p.date(),user.tenantId(),employeeId);
            }
            Case result=jdbc.queryForObject("""
                UPDATE personnel_hr_case SET status=?,decided_by=?,decided_at=now()
                WHERE tenant_id=? AND employee_id=? AND id=?
                RETURNING id,case_type,status,effective_date,requested_by,decided_by
                """,(rs,i)->map(rs),decision,user.id(),user.tenantId(),employeeId,caseId);
            audit(user,"HR_CASE_"+decision,caseId);
            return result;
        });
    }
    private record Pending(String type,LocalDate date,UUID requestedBy) {}
    private String employee(UUID tenant,UUID id,boolean lock) {
        List<String> rows=jdbc.query("SELECT employment_status FROM personnel_employee WHERE tenant_id=? AND id=?"
            +(lock?" FOR UPDATE":""),(rs,i)->rs.getString(1),tenant,id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
        return rows.get(0);
    }
    private void audit(HrmUserPrincipal user,String action,UUID id) {
        jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,? ,?,'personnel_hr_case',?)",
            user.tenantId(),user.id(),action,id);
    }
    private static Case map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Case(rs.getObject("id",UUID.class),rs.getString("case_type"),rs.getString("status"),
            rs.getObject("effective_date",LocalDate.class),rs.getObject("requested_by",UUID.class),
            rs.getObject("decided_by",UUID.class));
    }
}
