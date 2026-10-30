package com.apexmedsuite.hrm.personnel;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Restricted endpoint for sensitive employee data. Never include private fields in directory responses. */
@RestController
@RequestMapping("/api/v1/personnel/employees/{employeeId}/private")
public class EmployeePrivateDataController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    private final PrivateDataCipher cipher;
    public EmployeePrivateDataController(JdbcTemplate jdbc,TenantJdbcExecutor tenants,PrivateDataCipher cipher) {
        this.jdbc=jdbc;this.tenants=tenants;this.cipher=cipher;
    }
    public record PrivateData(String governmentId,String contactEmail,String mobile,String bankAccount) {}
    public record UpdatePrivateData(@Size(max=100) String governmentId,
        @Size(max=254) String contactEmail,@Size(max=40) String mobile,
        @Size(max=100) String bankAccount) {}
    @GetMapping
    public PrivateData get(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId) {
        return tenants.inTenant(user.tenantId(),()->{
            checkEmployee(user.tenantId(),employeeId);
            List<PrivateData> result=jdbc.query("""
                SELECT government_id_ciphertext,contact_email_ciphertext,mobile_ciphertext,bank_account_ciphertext
                FROM personnel_private_data WHERE tenant_id=? AND employee_id=?
                """,(rs,n)->new PrivateData(
                    decode(rs.getString(1),user.tenantId(),employeeId,"governmentId"),
                    decode(rs.getString(2),user.tenantId(),employeeId,"contactEmail"),
                    decode(rs.getString(3),user.tenantId(),employeeId,"mobile"),
                    decode(rs.getString(4),user.tenantId(),employeeId,"bankAccount")),
                user.tenantId(),employeeId);
            return result.isEmpty()?new PrivateData(null,null,null,null):result.get(0);
        });
    }
    @PutMapping
    public void update(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                       @Valid @RequestBody UpdatePrivateData input) {
        tenants.inTenant(user.tenantId(),()->{
            checkEmployee(user.tenantId(),employeeId);
            jdbc.update("""
                INSERT INTO personnel_private_data
                (tenant_id,employee_id,government_id_ciphertext,contact_email_ciphertext,
                  mobile_ciphertext,bank_account_ciphertext,updated_by)
                VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(tenant_id,employee_id) DO UPDATE SET
                government_id_ciphertext=EXCLUDED.government_id_ciphertext,
                contact_email_ciphertext=EXCLUDED.contact_email_ciphertext,
                mobile_ciphertext=EXCLUDED.mobile_ciphertext,
                bank_account_ciphertext=EXCLUDED.bank_account_ciphertext,
                updated_by=EXCLUDED.updated_by,updated_at=now()
                """,user.tenantId(),employeeId,encode(input.governmentId(),user.tenantId(),employeeId,"governmentId"),
                encode(input.contactEmail(),user.tenantId(),employeeId,"contactEmail"),
                encode(input.mobile(),user.tenantId(),employeeId,"mobile"),
                encode(input.bankAccount(),user.tenantId(),employeeId,"bankAccount"),user.id());
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'EMPLOYEE_PRIVATE_DATA_UPDATED','personnel_employee',?)
                """,user.tenantId(),user.id(),employeeId);
            return null;
        });
    }
    private String encode(String value,UUID tenant,UUID employee,String field) {
        return cipher.encrypt(value,tenant+":"+employee+":"+field);
    }
    private String decode(String value,UUID tenant,UUID employee,String field) {
        return cipher.decrypt(value,tenant+":"+employee+":"+field);
    }
    private void checkEmployee(UUID tenant,UUID employee) {
        Integer n=jdbc.queryForObject("SELECT count(*) FROM personnel_employee WHERE tenant_id=? AND id=?",
            Integer.class,tenant,employee);
        if(n==null||n==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
    }
}
