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

/** Safe lifecycle controls: no destructive removal before approved purge/retention infrastructure. */
@RestController
@RequestMapping("/api/v1/personnel/employees/{employeeId}/documents")
public class DocumentLifecycleController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public DocumentLifecycleController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record Lifecycle(UUID id,UUID familyId,int version,boolean legalHold,
                            LocalDate retainUntil,boolean deleted) {}
    public record Hold(@NotNull Boolean enabled,@Size(max=500) String reason) {}
    public record Retention(@NotNull @FutureOrPresent LocalDate retainUntil) {}
    @GetMapping("/{documentId}/lifecycle")
    public Lifecycle detail(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                            @PathVariable UUID documentId) {
        return tenants.inTenant(user.tenantId(),()->load(user.tenantId(),employeeId,documentId,false));
    }
    @PutMapping("/{documentId}/hold")
    public Lifecycle hold(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                          @PathVariable UUID documentId,@Valid @RequestBody Hold input) {
        return tenants.inTenant(user.tenantId(),()->{
            load(user.tenantId(),employeeId,documentId,true);
            String reason=Boolean.TRUE.equals(input.enabled())?input.reason():null;
            if(Boolean.TRUE.equals(input.enabled())&&(reason==null||reason.isBlank()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Legal hold requires reason");
            jdbc.update("""
                UPDATE personnel_document_metadata SET legal_hold=?,legal_hold_reason=?
                WHERE tenant_id=? AND employee_id=? AND id=? AND deleted_at IS NULL
                """,input.enabled(),reason,user.tenantId(),employeeId,documentId);
            audit(user,"DOCUMENT_LEGAL_HOLD_UPDATED",documentId);
            return load(user.tenantId(),employeeId,documentId,false);
        });
    }
    @PutMapping("/{documentId}/retention")
    public Lifecycle retention(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                               @PathVariable UUID documentId,@Valid @RequestBody Retention input) {
        return tenants.inTenant(user.tenantId(),()->{
            Lifecycle existing=load(user.tenantId(),employeeId,documentId,true);
            if(existing.legalHold())throw new ResponseStatusException(HttpStatus.CONFLICT,"Legal hold prevents retention changes");
            if(existing.retainUntil()!=null&&input.retainUntil().isBefore(existing.retainUntil()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Retention cannot be shortened");
            jdbc.update("""
                UPDATE personnel_document_metadata SET retain_until=?
                WHERE tenant_id=? AND employee_id=? AND id=? AND deleted_at IS NULL
                """,input.retainUntil(),user.tenantId(),employeeId,documentId);
            audit(user,"DOCUMENT_RETENTION_EXTENDED",documentId);
            return load(user.tenantId(),employeeId,documentId,false);
        });
    }
    @PostMapping("/{documentId}/tombstone")
    public Lifecycle tombstone(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                               @PathVariable UUID documentId) {
        return tenants.inTenant(user.tenantId(),()->{
            Lifecycle existing=load(user.tenantId(),employeeId,documentId,true);
            if(existing.legalHold()||existing.retainUntil()==null||!existing.retainUntil().isBefore(LocalDate.now()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Active hold or retention prohibits deletion");
            jdbc.update("""
                UPDATE personnel_document_metadata SET deleted_at=now(),deleted_by=?
                WHERE tenant_id=? AND employee_id=? AND id=? AND deleted_at IS NULL
                """,user.id(),user.tenantId(),employeeId,documentId);
            audit(user,"DOCUMENT_TOMBSTONED_PENDING_PURGE",documentId);
            return load(user.tenantId(),employeeId,documentId,false);
        });
    }
    private Lifecycle load(UUID tenant,UUID employee,UUID document,boolean lock) {
        List<Lifecycle> rows=jdbc.query("""
            SELECT id,document_family_id,version,legal_hold,retain_until,deleted_at IS NOT NULL
            FROM personnel_document_metadata WHERE tenant_id=? AND employee_id=? AND id=?
            """+(lock?" FOR UPDATE":""),(rs,i)->new Lifecycle(rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class),rs.getInt(3),rs.getBoolean(4),
                rs.getObject(5,LocalDate.class),rs.getBoolean(6)),tenant,employee,document);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Document not found");
        if(lock&&rows.get(0).deleted())throw new ResponseStatusException(HttpStatus.CONFLICT,"Document already tombstoned");
        return rows.get(0);
    }
    private void audit(HrmUserPrincipal user,String action,UUID id) {
        jdbc.update("INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id) VALUES(?,?,?,'personnel_document_metadata',?)",
            user.tenantId(),user.id(),action,id);
    }
}
