package com.apexmedsuite.hrm.timekeeping;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * M2.9.1 restricted device enrollment and replay-safe authentication proof.
 * None of these endpoints creates attendance, GPS, biometrics, payroll, or
 * device-based employee authentication. The normal employee MFA bearer
 * is required in addition to the device secret for attestation.
 */
@RestController
@RequestMapping("/api/v1/employee-self-service")
public class EmployeeDeviceController {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public EmployeeDeviceController(JdbcTemplate jdbc,TenantJdbcExecutor tenants){
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record NewDevice(@NotBlank @Size(min=3,max=80) String displayName){}
    public record Device(UUID id,UUID employeeId,String displayName,String status,Instant credentialExpiresAt){}
    public record Activation(UUID deviceId,String credential,Instant credentialExpiresAt,
                             String classification){}
    public record Attestation(@NotNull UUID nonce,@NotNull Instant timestamp){}
    public record Result(UUID deviceId,UUID nonce,String classification){}
    private record Owned(UUID employeeId,UUID identityId){}

    @GetMapping("/devices")
    public List<Device> mine(@AuthenticationPrincipal HrmUserPrincipal actor){
        return tenants.inTenant(actor.tenantId(),()->{
            Owned employee=owned(actor,false);
            return jdbc.query("""
                SELECT id,employee_id,display_name,status,credential_expires_at
                FROM time_ess_device WHERE tenant_id=? AND employee_id=? AND identity_id=?
                ORDER BY requested_at DESC LIMIT 100
                """,(rs,n)->row(rs),actor.tenantId(),employee.employeeId(),actor.id());
        });
    }
    @PostMapping("/devices")
    @ResponseStatus(HttpStatus.CREATED)
    public Device enroll(@AuthenticationPrincipal HrmUserPrincipal actor,
                         @Valid @RequestBody NewDevice input){
        return tenants.inTenant(actor.tenantId(),()->{
            Owned employee=owned(actor,true);
            Integer active=jdbc.queryForObject("""
                SELECT count(*) FROM time_ess_device
                WHERE tenant_id=? AND employee_id=? AND status IN ('PENDING','APPROVED','ACTIVE')
                """,Integer.class,actor.tenantId(),employee.employeeId());
            if(active!=null&&active>=3)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Maximum three devices awaiting or using enrollment");
            Device device=jdbc.queryForObject("""
                INSERT INTO time_ess_device(tenant_id,employee_id,identity_id,display_name)
                VALUES(?,?,?,?) RETURNING id,employee_id,display_name,status,credential_expires_at
                """,(rs,n)->row(rs),actor.tenantId(),employee.employeeId(),actor.id(),
                input.displayName().trim());
            audit(actor,"TIME_ESS_DEVICE_REQUESTED","time_ess_device",device.id());
            return device;
        });
    }
    @GetMapping("/admin/devices")
    public List<Device> adminList(@AuthenticationPrincipal HrmUserPrincipal actor,
                                  @RequestParam UUID employeeId){
        return tenants.inTenant(actor.tenantId(),()->jdbc.query("""
            SELECT id,employee_id,display_name,status,credential_expires_at
            FROM time_ess_device WHERE tenant_id=? AND employee_id=?
            ORDER BY requested_at DESC LIMIT 100
            """,(rs,n)->row(rs),actor.tenantId(),employeeId));
    }
    @PostMapping("/admin/devices/{deviceId}/review")
    public Device review(@AuthenticationPrincipal HrmUserPrincipal actor,
                         @PathVariable UUID deviceId,
                         @RequestParam @Pattern(regexp="APPROVED|REJECTED") String decision){
        return tenants.inTenant(actor.tenantId(),()->{
            List<UUID> rows=jdbc.query("""
                SELECT d.identity_id FROM time_ess_device d
                JOIN time_employee_identity_link l
                  ON l.tenant_id=d.tenant_id AND l.identity_id=d.identity_id
                 AND l.employee_id=d.employee_id AND l.revoked_at IS NULL
                JOIN personnel_employee e ON e.tenant_id=d.tenant_id AND e.id=d.employee_id
                WHERE d.tenant_id=? AND d.id=? AND d.status='PENDING'
                  AND e.employment_status IN ('ACTIVE','ON_LEAVE')
                FOR UPDATE OF d
                """,(rs,n)->rs.getObject(1,UUID.class),actor.tenantId(),deviceId);
            if(rows.isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Pending device or current verified employee link not found");
            if(rows.get(0).equals(actor.id()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Independent device reviewer required");
            Device device=jdbc.queryForObject("""
                UPDATE time_ess_device SET status=?,reviewed_by=?,reviewed_at=now()
                WHERE tenant_id=? AND id=? AND status='PENDING'
                RETURNING id,employee_id,display_name,status,credential_expires_at
                """,(rs,n)->row(rs),decision,actor.id(),actor.tenantId(),deviceId);
            audit(actor,"TIME_ESS_DEVICE_"+decision,"time_ess_device",deviceId);
            return device;
        });
    }
    @PostMapping("/devices/{deviceId}/activate")
    public Activation activate(@AuthenticationPrincipal HrmUserPrincipal actor,
                               @PathVariable UUID deviceId){
        return tenants.inTenant(actor.tenantId(),()->{
            Owned employee=owned(actor,true);
            List<UUID> approved=jdbc.query("""
                SELECT id FROM time_ess_device
                WHERE tenant_id=? AND id=? AND employee_id=? AND identity_id=?
                AND status='APPROVED' FOR UPDATE
                """,(rs,n)->rs.getObject(1,UUID.class),actor.tenantId(),deviceId,
                employee.employeeId(),actor.id());
            if(approved.isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Device not approved or already activated");
            byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);
            String raw=HexFormat.of().formatHex(bytes);
            byte[] digest=hash(raw);
            Instant expiry=Instant.now().plus(Duration.ofDays(30));
            jdbc.update("""
                UPDATE time_ess_device SET status='ACTIVE',activated_at=now(),
                 credential_sha256=?,credential_expires_at=?
                WHERE tenant_id=? AND id=? AND status='APPROVED'
                """,HexFormat.of().formatHex(digest),java.sql.Timestamp.from(expiry),
                actor.tenantId(),deviceId);
            audit(actor,"TIME_ESS_DEVICE_ACTIVATED","time_ess_device",deviceId);
            return new Activation(deviceId,raw,expiry,"ONE_TIME_DEVICE_CREDENTIAL_NO_CLOCK_IN");
        });
    }
    @PostMapping("/devices/{deviceId}/attest")
    public Result attest(@AuthenticationPrincipal HrmUserPrincipal actor,
                         @PathVariable UUID deviceId,
                         @RequestHeader("X-Device-Credential") String credential,
                         @Valid @RequestBody Attestation input){
        if(!credential.matches("[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Invalid device credential");
        Instant now=Instant.now();
        if(input.timestamp().isBefore(now.minus(Duration.ofMinutes(5)))
            ||input.timestamp().isAfter(now.plus(Duration.ofMinutes(5))))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Device timestamp outside accepted skew");
        return tenants.inTenant(actor.tenantId(),()->{
            Owned employee=owned(actor,true);
            List<String> secrets=jdbc.query("""
                SELECT credential_sha256 FROM time_ess_device
                WHERE tenant_id=? AND employee_id=? AND identity_id=? AND id=?
                AND status='ACTIVE' AND credential_expires_at>now()
                FOR UPDATE
                """,(rs,n)->rs.getString(1),actor.tenantId(),employee.employeeId(),
                actor.id(),deviceId);
            if(secrets.isEmpty()||!MessageDigest.isEqual(
                    HexFormat.of().parseHex(secrets.get(0)),hash(credential)))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Device credential not valid");
            Integer used=jdbc.queryForObject("""
                SELECT count(*) FROM time_ess_device_attestation
                WHERE tenant_id=? AND device_id=? AND nonce=?
                """,Integer.class,actor.tenantId(),deviceId,input.nonce());
            if(used!=null&&used>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Device nonce replay");
            UUID attestation=jdbc.queryForObject("""
                INSERT INTO time_ess_device_attestation(tenant_id,device_id,nonce)
                VALUES(?,?,?) RETURNING id
                """,UUID.class,actor.tenantId(),deviceId,input.nonce());
            audit(actor,"TIME_ESS_DEVICE_ATTESTED","time_ess_device_attestation",attestation);
            return new Result(deviceId,input.nonce(),"AUTHENTICATION_PROOF_ONLY_NO_ATTENDANCE");
        });
    }
    @PostMapping("/devices/{deviceId}/revoke")
    public Device revoke(@AuthenticationPrincipal HrmUserPrincipal actor,
                         @PathVariable UUID deviceId){
        return tenants.inTenant(actor.tenantId(),()->{
            Owned employee=owned(actor,true);
            return revokeDevice(actor,deviceId,employee.employeeId());
        });
    }
    @PostMapping("/admin/devices/{deviceId}/revoke")
    public Device adminRevoke(@AuthenticationPrincipal HrmUserPrincipal actor,
                              @PathVariable UUID deviceId){
        return tenants.inTenant(actor.tenantId(),()->revokeDevice(actor,deviceId,null));
    }
    private Device revokeDevice(HrmUserPrincipal actor,UUID deviceId,UUID employeeId){
        List<Device> found=jdbc.query("""
            SELECT id,employee_id,display_name,status,credential_expires_at FROM time_ess_device
            WHERE tenant_id=? AND id=? AND (?::uuid IS NULL OR employee_id=?)
              AND status IN ('PENDING','APPROVED','ACTIVE') FOR UPDATE
            """,(rs,n)->row(rs),actor.tenantId(),deviceId,employeeId,employeeId);
        if(found.isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Revocable device not found");
        jdbc.update("""
            UPDATE time_ess_device SET status='REVOKED',revoked_at=now(),
              revoked_by=?,credential_sha256=NULL,credential_expires_at=NULL
            WHERE tenant_id=? AND id=?
            """,actor.id(),actor.tenantId(),deviceId);
        audit(actor,"TIME_ESS_DEVICE_REVOKED","time_ess_device",deviceId);
        Device d=found.get(0);
        return new Device(d.id(),d.employeeId(),d.displayName(),"REVOKED",null);
    }
    private Owned owned(HrmUserPrincipal actor,boolean lock){
        List<Owned> found=jdbc.query("""
            SELECT l.employee_id,l.identity_id FROM time_employee_identity_link l
            JOIN personnel_employee e ON e.tenant_id=l.tenant_id AND e.id=l.employee_id
            WHERE l.tenant_id=? AND l.identity_id=? AND l.revoked_at IS NULL
              AND e.employment_status IN ('ACTIVE','ON_LEAVE')
            """+(lock?" FOR UPDATE OF e":""),(rs,n)->new Owned(
                rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)),
            actor.tenantId(),actor.id());
        if(found.size()!=1)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Current verified employee binding required");
        return found.get(0);
    }
    private void audit(HrmUserPrincipal actor,String action,String entity,UUID id){
        jdbc.update("""
            INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
            VALUES(?,?,?,?,?)
            """,actor.tenantId(),actor.id(),action,entity,id);
    }
    private static byte[] hash(String value){
        try{return MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.US_ASCII));}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    private static Device row(java.sql.ResultSet rs)throws java.sql.SQLException{
        java.sql.Timestamp expiry=rs.getTimestamp("credential_expires_at");
        return new Device(rs.getObject("id",UUID.class),
            rs.getObject("employee_id",UUID.class),rs.getString("display_name"),
            rs.getString("status"),expiry==null?null:expiry.toInstant());
    }
}
