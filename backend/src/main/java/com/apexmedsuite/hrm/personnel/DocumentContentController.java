package com.apexmedsuite.hrm.personnel;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Developer-preview encrypted PDF storage; no malware-scanner integration yet. */
@RestController
@RequestMapping("/api/v1/personnel/employees/{employeeId}/documents")
public class DocumentContentController {
    private static final int MAX_BYTES=2*1024*1024;
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    private final DocumentContentCipher cipher;
    public DocumentContentController(JdbcTemplate jdbc,TenantJdbcExecutor tenants,DocumentContentCipher cipher) {
        this.jdbc=jdbc;this.tenants=tenants;this.cipher=cipher;
    }
    public record Stored(UUID id,String sha256,long sizeBytes) {}
    @PostMapping(value="/upload",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Stored upload(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
         @RequestParam String category,@RequestPart("file") MultipartFile file) throws IOException {
        if(!List.of("CONTRACT","CERTIFICATION","ONBOARDING","SEPARATION","OTHER").contains(category))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported document category");
        if(file.isEmpty()||file.getSize()>MAX_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Document exceeds 2 MiB limit");
        byte[] bytes=file.getBytes();
        if(bytes.length<8||bytes.length>MAX_BYTES||bytes[0]!='%'||bytes[1]!='P'||bytes[2]!='D'||bytes[3]!='F'||bytes[4]!='-')
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"Only PDF content is accepted");
        String filename=file.getOriginalFilename();
        if(filename==null||filename.isBlank()||filename.length()>200||filename.contains("/")||filename.contains("\\")
                ||filename.contains("..")||!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid PDF filename");
        return tenants.inTenant(user.tenantId(),()->{
            Integer count=jdbc.queryForObject("""
                SELECT count(*) FROM personnel_employee WHERE tenant_id=? AND id=?
                """,Integer.class,user.tenantId(),employeeId);
            if(count==null||count==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
            UUID documentId=UUID.randomUUID();
            String digest;
            try{digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
            catch(Exception ex){throw new IllegalStateException("SHA-256 unavailable",ex);}
            DocumentContentCipher.Encrypted encrypted=cipher.encrypt(bytes,user.tenantId()+":"+employeeId+":"+documentId);
            jdbc.update("""
                INSERT INTO personnel_document_metadata
                 (id,tenant_id,employee_id,category,filename,sha256,size_bytes,created_by)
                VALUES(?,?,?,?,?,?,?,?)
                """,documentId,user.tenantId(),employeeId,category,filename,digest,bytes.length,user.id());
            jdbc.update("""
                INSERT INTO personnel_document_content(tenant_id,document_id,nonce,ciphertext)
                VALUES(?,?,?,?)
                """,user.tenantId(),documentId,encrypted.nonce(),encrypted.ciphertext());
            audit(user,"DOCUMENT_CONTENT_UPLOADED",documentId);
            return new Stored(documentId,digest,bytes.length);
        });
    }
    @PostMapping(value="/{documentId}/versions",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Stored replace(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                          @PathVariable UUID documentId,@RequestPart("file") MultipartFile file) throws IOException {
        if(file.isEmpty()||file.getSize()>MAX_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"File exceeds 2 MiB limit");
        byte[] bytes=file.getBytes();
        if(bytes.length<8||bytes.length>MAX_BYTES||bytes[0]!='%'||bytes[1]!='P'||bytes[2]!='D'||bytes[3]!='F'||bytes[4]!='-')
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"PDF required");
        String filename=file.getOriginalFilename();
        if(filename==null||filename.isBlank()||filename.length()>200||filename.contains("/")||filename.contains("\\\\")
           ||filename.contains("..")||!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid filename");
        return tenants.inTenant(user.tenantId(),()->{
            jdbc.query("SELECT id FROM personnel_employee WHERE tenant_id=? AND id=? FOR UPDATE",
                (rs,i)->rs.getObject(1,UUID.class),user.tenantId(),employeeId);
            List<Object[]> parent=jdbc.query("""
                SELECT document_family_id,category,version,legal_hold,retain_until FROM personnel_document_metadata
                WHERE tenant_id=? AND employee_id=? AND id=? AND deleted_at IS NULL FOR UPDATE
                """,(rs,i)->new Object[]{rs.getObject(1,UUID.class),rs.getString(2),
                     rs.getInt(3),rs.getBoolean(4),rs.getObject(5,java.time.LocalDate.class)},
                     user.tenantId(),employeeId,documentId);
            if(parent.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Document not found");
            Object[] p=parent.get(0);
            if(Boolean.TRUE.equals(p[3]))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Legal hold prohibits version replacement");
            Integer latest=jdbc.queryForObject("""
                SELECT max(version) FROM personnel_document_metadata
                WHERE tenant_id=? AND employee_id=? AND document_family_id=?
                """,Integer.class,user.tenantId(),employeeId,p[0]);
            if(latest==null||latest!=(Integer)p[2])
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Only newest version may be replaced");
            UUID newId=UUID.randomUUID();
            String digest;
            try {digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
            catch(Exception ex){throw new IllegalStateException("SHA-256 failure",ex);}
            DocumentContentCipher.Encrypted encrypted=cipher.encrypt(bytes,user.tenantId()+":"+employeeId+":"+newId);
            jdbc.update("""
                INSERT INTO personnel_document_metadata
                 (id,tenant_id,employee_id,category,filename,sha256,size_bytes,created_by,
                  document_family_id,version,retain_until)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """,newId,user.tenantId(),employeeId,p[1],filename,digest,bytes.length,user.id(),
                    p[0],latest+1,p[4]);
            jdbc.update("""
                INSERT INTO personnel_document_content(tenant_id,document_id,nonce,ciphertext)
                VALUES(?,?,?,?)
                """,user.tenantId(),newId,encrypted.nonce(),encrypted.ciphertext());
            audit(user,"DOCUMENT_VERSION_UPLOADED",newId);
            return new Stored(newId,digest,bytes.length);
        });
    }
    @GetMapping("/{documentId}/content")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                                           @PathVariable UUID documentId) {
        return tenants.inTenant(user.tenantId(),()->{
            List<byte[]> decrypted=jdbc.query("""
                SELECT c.nonce,c.ciphertext FROM personnel_document_content c
                JOIN personnel_document_metadata m ON m.tenant_id=c.tenant_id AND m.id=c.document_id
                WHERE c.tenant_id=? AND m.employee_id=? AND m.id=?
                AND m.deleted_at IS NULL AND m.review_status='RELEASED' AND m.scan_status='CLEAN' AND (m.expires_on IS NULL OR m.expires_on>=CURRENT_DATE)
                AND (m.retain_until IS NULL OR m.retain_until>=CURRENT_DATE)
                """,(rs,i)->cipher.decrypt(new DocumentContentCipher.Encrypted(
                    rs.getBytes(1),rs.getBytes(2)),user.tenantId()+":"+employeeId+":"+documentId),
                    user.tenantId(),employeeId,documentId);
            if(decrypted.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Document content unavailable");
            audit(user,"DOCUMENT_CONTENT_DOWNLOADED",documentId);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"employee-document.pdf\"")
                .header("X-Content-Type-Options","nosniff")
                .header("Cache-Control","no-store")
                .body(decrypted.get(0));
        });
    }
    private void audit(HrmUserPrincipal user,String action,UUID id) {
        jdbc.update("""
            INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
            VALUES(?,?,?,'personnel_document_metadata',?)
            """,user.tenantId(),user.id(),action,id);
    }
}
