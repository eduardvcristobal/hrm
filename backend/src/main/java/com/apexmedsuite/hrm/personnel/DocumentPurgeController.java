package com.apexmedsuite.hrm.personnel;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Removes live encrypted bytes only. Backups and WAL remain subject to separate retention controls. */
@RestController
@RequestMapping("/api/v1/personnel/employees/{employeeId}/documents/{documentId}/purge")
public class DocumentPurgeController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public DocumentPurgeController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record Purge(UUID id,String status,UUID requestedBy,UUID executedBy) {}
    private record DocumentState(boolean hold,LocalDate retainUntil,boolean tombstoned) {}
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Purge request(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                         @PathVariable UUID documentId) {
        return tenants.inTenant(user.tenantId(),()->{
            eligible(user.tenantId(),employeeId,documentId);
            int existing=jdbc.queryForObject("""
                SELECT count(*) FROM personnel_document_content WHERE tenant_id=? AND document_id=?
                """,Integer.class,user.tenantId(),documentId);
            if(existing!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Document content already absent");
            jdbc.update("""
                INSERT INTO personnel_document_purge_request(tenant_id,document_id,requested_by)
                VALUES(?,?,?) ON CONFLICT(tenant_id,document_id) DO NOTHING
                """,user.tenantId(),documentId,user.id());
            Purge result=getPurge(user.tenantId(),documentId,false);
            audit(user,"DOCUMENT_PURGE_REQUESTED",documentId);
            return result;
        });
    }
    @PostMapping("/{requestId}/execute")
    public Purge execute(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                         @PathVariable UUID documentId,@PathVariable UUID requestId) {
        return tenants.inTenant(user.tenantId(),()->{
            eligible(user.tenantId(),employeeId,documentId);
            List<Purge> pending=jdbc.query("""
                SELECT id,status,requested_by,executed_by FROM personnel_document_purge_request
                WHERE tenant_id=? AND document_id=? AND id=? AND status='PENDING' FOR UPDATE
                """,(rs,i)->new Purge(rs.getObject(1,UUID.class),rs.getString(2),
                    rs.getObject(3,UUID.class),rs.getObject(4,UUID.class)),user.tenantId(),documentId,requestId);
            if(pending.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Pending purge not found");
            if(pending.get(0).requestedBy().equals(user.id()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Independent purge executor required");
            int deleted=jdbc.update("""
                DELETE FROM personnel_document_content WHERE tenant_id=? AND document_id=?
                """,user.tenantId(),documentId);
            if(deleted!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Encrypted content missing");
            jdbc.update("""
                UPDATE personnel_document_purge_request SET status='EXECUTED',executed_by=?,executed_at=now()
                WHERE tenant_id=? AND document_id=? AND id=? AND status='PENDING'
                """,user.id(),user.tenantId(),documentId,requestId);
            audit(user,"DOCUMENT_CIPHERTEXT_PURGED",documentId);
            return getPurge(user.tenantId(),documentId,false);
        });
    }
    @GetMapping
    public List<Purge> list(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                            @PathVariable UUID documentId) {
        return tenants.inTenant(user.tenantId(),()->{
            ensureDocument(user.tenantId(),employeeId,documentId,false);
            return jdbc.query("""
                SELECT id,status,requested_by,executed_by FROM personnel_document_purge_request
                WHERE tenant_id=? AND document_id=?
                """,(rs,i)->new Purge(rs.getObject(1,UUID.class),rs.getString(2),
                    rs.getObject(3,UUID.class),rs.getObject(4,UUID.class)),user.tenantId(),documentId);
        });
    }
    private DocumentState ensureDocument(UUID tenant,UUID employee,UUID doc,boolean lock) {
        List<DocumentState> rows=jdbc.query("""
            SELECT legal_hold,retain_until,deleted_at IS NOT NULL FROM personnel_document_metadata
            WHERE tenant_id=? AND employee_id=? AND id=?
            """+(lock?" FOR UPDATE":""),(rs,i)->new DocumentState(rs.getBoolean(1),
                rs.getObject(2,LocalDate.class),rs.getBoolean(3)),tenant,employee,doc);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Document not found");
        return rows.get(0);
    }
    private void eligible(UUID tenant,UUID employee,UUID doc) {
        DocumentState state=ensureDocument(tenant,employee,doc,true);
        if(state.hold()||!state.tombstoned()||state.retainUntil()==null||
           !state.retainUntil().isBefore(LocalDate.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Document is held, active or still retained");
    }
    private Purge getPurge(UUID tenant,UUID doc,boolean lock) {
        return jdbc.queryForObject("""
            SELECT id,status,requested_by,executed_by FROM personnel_document_purge_request
            WHERE tenant_id=? AND document_id=?
            """+(lock?" FOR UPDATE":""),(rs,i)->new Purge(rs.getObject(1,UUID.class),
                rs.getString(2),rs.getObject(3,UUID.class),rs.getObject(4,UUID.class)),tenant,doc);
    }
    private void audit(HrmUserPrincipal user,String action,UUID doc) {
        jdbc.update("""
            INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
            VALUES(?,?,?,'personnel_document_metadata',?)
            """,user.tenantId(),user.id(),action,doc);
    }
}
