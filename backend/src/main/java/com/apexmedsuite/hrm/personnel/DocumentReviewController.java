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
    private final DocumentContentCipher cipher;
    private final DocumentMalwareScanner scanner;
    public DocumentReviewController(JdbcTemplate jdbc,TenantJdbcExecutor tenants,DocumentContentCipher cipher,DocumentMalwareScanner scanner) {
        this.jdbc=jdbc;this.tenants=tenants;this.cipher=cipher;this.scanner=scanner;
    }
    public record ReviewRequest(@NotBlank @Pattern(regexp="RELEASED|REJECTED") String status,
                                @FutureOrPresent LocalDate retainUntil) {}
    public record ReviewResult(UUID documentId,String status,LocalDate retainUntil) {}
    /** Scanner failure never changes a document into RELEASED. */
    @PostMapping("/{documentId}/scan")
    public String scan(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                       @PathVariable UUID documentId) {
        return tenants.inTenant(user.tenantId(),()->{
            List<DocumentContentCipher.Encrypted> content=jdbc.query("""
                SELECT c.nonce,c.ciphertext FROM personnel_document_content c
                JOIN personnel_document_metadata m ON m.tenant_id=c.tenant_id AND m.id=c.document_id
                WHERE m.tenant_id=? AND m.employee_id=? AND m.id=? AND m.review_status='QUARANTINED'
                FOR UPDATE OF m
                """,(rs,i)->new DocumentContentCipher.Encrypted(rs.getBytes(1),rs.getBytes(2)),
                    user.tenantId(),employeeId,documentId);
            if(content.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Quarantined document content not found");
            byte[] plain=cipher.decrypt(content.get(0),user.tenantId()+":"+employeeId+":"+documentId);
            DocumentMalwareScanner.Verdict verdict=scanner.scan(plain);
            java.util.Arrays.fill(plain,(byte)0);
            jdbc.update("""
                UPDATE personnel_document_metadata SET scan_status=?,scanned_at=now()
                WHERE tenant_id=? AND employee_id=? AND id=? AND review_status='QUARANTINED' AND deleted_at IS NULL
                """,verdict.name(),user.tenantId(),employeeId,documentId);
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,? ,?,'personnel_document_metadata',?)
                """,user.tenantId(),user.id(),"DOCUMENT_SCAN_"+verdict.name(),documentId);
            return verdict.name();
        });
    }
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
                String scanStatus=jdbc.queryForObject("""
                    SELECT scan_status FROM personnel_document_metadata
                    WHERE tenant_id=? AND employee_id=? AND id=?
                    """,String.class,user.tenantId(),employeeId,documentId);
                if(!"CLEAN".equals(scanStatus))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Verified clean malware scan required");
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
