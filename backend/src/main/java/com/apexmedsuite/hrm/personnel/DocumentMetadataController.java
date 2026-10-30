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

/** Document inventory only. Content upload/download must await secure object-store integration. */
@RestController
@RequestMapping("/api/v1/personnel/employees/{employeeId}/documents")
public class DocumentMetadataController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public DocumentMetadataController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record Document(UUID id,String category,String filename,String sha256,long sizeBytes,
                           LocalDate expiresOn,int version) {}
    public record NewDocument(
        @NotBlank @Pattern(regexp="CONTRACT|IDENTITY|CERTIFICATION|ONBOARDING|SEPARATION|OTHER") String category,
        @NotBlank @Size(max=200) String filename,
        @NotBlank @Pattern(regexp="[0-9a-f]{64}") String sha256,
        @NotNull @Min(1) @Max(10485760) Long sizeBytes,
        LocalDate expiresOn) {}
    @GetMapping
    public List<Document> list(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId) {
        return tenants.inTenant(user.tenantId(),()->{
            checkEmployee(user.tenantId(),employeeId);
            return jdbc.query("""
                SELECT id,category,filename,sha256,size_bytes,expires_on,version
                FROM personnel_document_metadata WHERE tenant_id=? AND employee_id=?
                ORDER BY created_at DESC LIMIT 200
                """,(rs,i)->map(rs),user.tenantId(),employeeId);
        });
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Document register(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                          @Valid @RequestBody NewDocument input) {
        return tenants.inTenant(user.tenantId(),()->{
            checkEmployee(user.tenantId(),employeeId);
            // Only record file hashes/metadata, not raw document content or unsecured links.
            String safeName=input.filename().trim();
            if(safeName.contains("/")||safeName.contains("\\")||safeName.contains(".."))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid filename");
            Document result=jdbc.queryForObject("""
                INSERT INTO personnel_document_metadata
                (tenant_id,employee_id,category,filename,sha256,size_bytes,expires_on,created_by)
                VALUES(?,?,?,?,?,?,?,?)
                RETURNING id,category,filename,sha256,size_bytes,expires_on,version
                """,(rs,i)->map(rs),user.tenantId(),employeeId,input.category(),safeName,
                   input.sha256(),input.sizeBytes(),input.expiresOn(),user.id());
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'DOCUMENT_METADATA_REGISTERED','personnel_document_metadata',?)
                """,user.tenantId(),user.id(),result.id());
            return result;
        });
    }
    private void checkEmployee(UUID tenantId,UUID employeeId) {
        Integer n=jdbc.queryForObject("SELECT count(*) FROM personnel_employee WHERE tenant_id=? AND id=?",
            Integer.class,tenantId,employeeId);
        if(n==null||n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
    }
    private static Document map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Document(rs.getObject("id",UUID.class),rs.getString("category"),
            rs.getString("filename"),rs.getString("sha256"),rs.getLong("size_bytes"),
            rs.getObject("expires_on",LocalDate.class),rs.getInt("version"));
    }
}
