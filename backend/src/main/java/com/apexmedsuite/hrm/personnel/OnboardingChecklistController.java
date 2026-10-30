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
@RequestMapping("/api/v1/personnel/employees/{employeeId}/hr-cases/{caseId}/requirements")
public class OnboardingChecklistController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public OnboardingChecklistController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc; this.tenants=tenants;
    }
    public record Requirement(UUID id,String code,String label,boolean mandatory,String status,UUID evidenceDocumentId) {}
    public record NewRequirement(
        @NotBlank @Pattern(regexp="[A-Z0-9_]+") @Size(max=40) String code,
        @NotBlank @Size(max=160) String label,boolean mandatory) {}
    public record Verification(@NotBlank @Pattern(regexp="VERIFIED|REJECTED") String status,
                               UUID evidenceDocumentId) {}
    @GetMapping
    public List<Requirement> list(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                                  @PathVariable UUID caseId) {
        return tenants.inTenant(user.tenantId(),()->{
            ensureCase(user.tenantId(),employeeId,caseId,false);
            return jdbc.query("""
                SELECT id,requirement_code,label,mandatory,status,evidence_document_id
                FROM personnel_onboarding_requirement WHERE tenant_id=? AND hr_case_id=?
                ORDER BY requirement_code LIMIT 100
                """,(rs,i)->row(rs),user.tenantId(),caseId);
        });
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Requirement create(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                              @PathVariable UUID caseId,@Valid @RequestBody NewRequirement input) {
        return tenants.inTenant(user.tenantId(),()->{
            ensureCase(user.tenantId(),employeeId,caseId,true);
            Requirement result=jdbc.queryForObject("""
                INSERT INTO personnel_onboarding_requirement(tenant_id,hr_case_id,requirement_code,label,mandatory)
                VALUES(?,?,?,?,?)
                RETURNING id,requirement_code,label,mandatory,status,evidence_document_id
                """,(rs,i)->row(rs),user.tenantId(),caseId,input.code(),input.label().trim(),input.mandatory());
            audit(user,"ONBOARDING_REQUIREMENT_ADDED",result.id());
            return result;
        });
    }
    @PatchMapping("/{requirementId}/verification")
    public Requirement verify(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                @PathVariable UUID caseId,@PathVariable UUID requirementId,@Valid @RequestBody Verification input) {
        return tenants.inTenant(user.tenantId(),()->{
            ensureCase(user.tenantId(),employeeId,caseId,true);
            if(input.evidenceDocumentId()!=null) {
                Integer n=jdbc.queryForObject("""
                    SELECT count(*) FROM personnel_document_metadata
                    WHERE tenant_id=? AND employee_id=? AND id=?
                    """,Integer.class,user.tenantId(),employeeId,input.evidenceDocumentId());
                if(n==null||n==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Evidence not found");
            }
            List<Requirement> changed=jdbc.query("""
                UPDATE personnel_onboarding_requirement
                SET status=?,evidence_document_id=?,verified_by=?,verified_at=now()
                WHERE tenant_id=? AND hr_case_id=? AND id=? AND status='PENDING'
                RETURNING id,requirement_code,label,mandatory,status,evidence_document_id
                """,(rs,i)->row(rs),input.status(),input.evidenceDocumentId(),user.id(),
                   user.tenantId(),caseId,requirementId);
            if(changed.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Requirement not pending");
            audit(user,"ONBOARDING_REQUIREMENT_"+input.status(),requirementId);
            return changed.get(0);
        });
    }
    private void ensureCase(UUID tenantId,UUID employeeId,UUID caseId,boolean pending) {
        Integer found=jdbc.queryForObject("""
            SELECT count(*) FROM personnel_hr_case
            WHERE tenant_id=? AND employee_id=? AND id=? AND case_type='ONBOARDING'
            """+(pending?" AND status='PENDING'":""),Integer.class,tenantId,employeeId,caseId);
        if(found==null||found==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Onboarding case not found");
    }
    private void audit(HrmUserPrincipal user,String action,UUID entityId) {
        jdbc.update("""
            INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
            VALUES(?,? ,?,'personnel_onboarding_requirement',?)
            """,user.tenantId(),user.id(),action,entityId);
    }
    private static Requirement row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Requirement(rs.getObject("id",UUID.class),rs.getString("requirement_code"),
            rs.getString("label"),rs.getBoolean("mandatory"),rs.getString("status"),
            rs.getObject("evidence_document_id",UUID.class));
    }
}
