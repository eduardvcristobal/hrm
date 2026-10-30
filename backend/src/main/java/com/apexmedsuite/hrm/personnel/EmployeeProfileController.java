package com.apexmedsuite.hrm.personnel;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Restricted employee profile. Government identifiers and contact PII are deliberately excluded. */
@RestController
@RequestMapping("/api/v1/personnel/employees/{employeeId}/profile")
public class EmployeeProfileController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public EmployeeProfileController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record Profile(UUID id,UUID companyId,UUID branchId,UUID departmentId,
       String employeeNumber,String firstName,String middleName,String lastName,
       String preferredName,String suffix,LocalDate hireDate,String employmentStatus,String employmentType) {}
    public record Names(@Size(max=120) String middleName,@Size(max=120) String preferredName,
                        @Size(max=30) String suffix) {}
    @GetMapping
    public Profile get(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId) {
        return tenants.inTenant(user.tenantId(),()->find(user.tenantId(),employeeId));
    }
    @PatchMapping
    public Profile update(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                        @Valid @RequestBody Names names) {
        return tenants.inTenant(user.tenantId(),()->{
            List<Profile> rows=jdbc.query("""
                UPDATE personnel_employee SET middle_name=?,preferred_name=?,suffix=?,updated_at=now()
                WHERE tenant_id=? AND id=?
                RETURNING id,company_id,branch_id,department_id,employee_number,first_name,middle_name,
                  last_name,preferred_name,suffix,hire_date,employment_status,employment_type
                """,(rs,i)->map(rs),clean(names.middleName()),clean(names.preferredName()),
                  clean(names.suffix()),user.tenantId(),employeeId);
            if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'EMPLOYEE_PROFILE_UPDATED','personnel_employee',?)
                """,user.tenantId(),user.id(),employeeId);
            return rows.get(0);
        });
    }
    private Profile find(UUID tenantId,UUID id) {
        List<Profile> rows=jdbc.query("""
            SELECT id,company_id,branch_id,department_id,employee_number,first_name,middle_name,
              last_name,preferred_name,suffix,hire_date,employment_status,employment_type
            FROM personnel_employee WHERE tenant_id=? AND id=?
            """,(rs,i)->map(rs),tenantId,id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
        return rows.get(0);
    }
    private static String clean(String s){return s==null||s.isBlank()?null:s.trim();}
    private static Profile map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Profile(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),
            rs.getObject("branch_id",UUID.class),rs.getObject("department_id",UUID.class),
            rs.getString("employee_number"),rs.getString("first_name"),rs.getString("middle_name"),
            rs.getString("last_name"),rs.getString("preferred_name"),rs.getString("suffix"),
            rs.getObject("hire_date",LocalDate.class),rs.getString("employment_status"),
            rs.getString("employment_type"));
    }
}
