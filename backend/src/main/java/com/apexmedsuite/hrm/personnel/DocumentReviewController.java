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

/** Manual document screening, not malware certification. Restrict use to test fixtures until AV integration. */
@RestController
@RequestMapping("/api/v1/personnel/employees/{employeeId}/documents")
public class DocumentReviewController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public DocumentReviewController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record ReviewRequest(@NotBlank @Pattern(regexp="RELEASED|REJECTED") String status,
                                @FutureOrPresent LocalDate retainUntil) {}
    public record ReviewResult(UUID documentId,String status,LocalDate retainUntil) {}
    @PostMapping("/{documentId}/review")
    public ReviewResult review(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                         @PathVariable UUID documentId,@Valid @RequestBody ReviewRequest input) {
        return tenants.inTenant(user.tenantId(),()->{
            List<UUID> creators=jdbc.query("""
                SELECT created_by FROM personnel_document_metadata
                WHERE tenant_id=? AND employee_id=? AND id=? AND review_status='QUARANTINED'
                FOR UPDATE
                """,(rs,i)->rs.getObject(1,UUID.class),user.tenantId(),employeeId,documentId);
            if(creators.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Document is not quarantined");
            if(creators.get(0).equals(user.id()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Independent reviewer required");
            if(input.status().equals("RELEASED")) {
                Integer contentCount=jdbc.queryForObject("""
                    SELECT count(*) FROM personnel_document_content WHERE tenant_id=? AND document_id=?
                    """,Integer.class,user.tenantId(),documentId);
                if(contentCount==null||contentCount!=1)
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Cannot release metadata-only document");
            }
            jdbc.update("""
                UPDATE personnel_document_metadata SET review_status=?,reviewed_by=?,reviewed_at=now(),retain_until=?
                WHERE tenant_id=? AND employee_id=? AND id=? AND review_status='QUARANTINED'
                """,input.status(),user.id(),input.retainUntil(),user.tenantId(),employeeId,documentId);
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,? ,?,'personnel_document_metadata',?)
                """,user.tenantId(),user.id(),"DOCUMENT_MANUAL_"+input.status(),documentId);
            return new ReviewResult(documentId,input.status(),input.retainUntil());
        });
    }
}
