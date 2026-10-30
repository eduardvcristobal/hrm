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

@RestController
@RequestMapping("/api/v1/personnel/employees")
public class EmployeeController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public EmployeeController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc; this.tenants=tenants;
    }
    public record Employee(UUID id,UUID companyId,String employeeNumber,String firstName,
                           String lastName,LocalDate hireDate,String employmentStatus) {}
    public record CreateEmployee(
        @NotNull UUID companyId,
        @NotBlank @Size(max=50) @Pattern(regexp="[A-Za-z0-9_-]+") String employeeNumber,
        @NotBlank @Size(max=120) String firstName,
        @NotBlank @Size(max=120) String lastName,
        @NotNull @PastOrPresent LocalDate hireDate
    ) {}
    @GetMapping
    public List<Employee> list(@AuthenticationPrincipal HrmUserPrincipal user,
             @RequestParam UUID companyId) {
        return tenants.inTenant(user.tenantId(),()->jdbc.query("""
            SELECT id,company_id,employee_number,first_name,last_name,hire_date,employment_status
            FROM personnel_employee WHERE tenant_id=? AND company_id=?
            ORDER BY last_name,first_name,employee_number LIMIT 200
            """,(rs,i)->row(rs),user.tenantId(),companyId));
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Employee create(@AuthenticationPrincipal HrmUserPrincipal user,
             @Valid @RequestBody CreateEmployee input) {
        return tenants.inTenant(user.tenantId(),()->{
            Integer count=jdbc.queryForObject("""
                SELECT count(*) FROM company WHERE tenant_id=? AND id=?
                """,Integer.class,user.tenantId(),input.companyId());
            if(count==null||count==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            Employee employee=jdbc.queryForObject("""
                INSERT INTO personnel_employee
                  (tenant_id,company_id,employee_number,first_name,last_name,hire_date)
                VALUES(?,?,?,?,?,?)
                RETURNING id,company_id,employee_number,first_name,last_name,hire_date,employment_status
                """,(rs,i)->row(rs),user.tenantId(),input.companyId(),input.employeeNumber(),
                input.firstName().trim(),input.lastName().trim(),input.hireDate());
            jdbc.update("""
                INSERT INTO personnel_employment_history
                  (tenant_id,employee_id,effective_date,employment_type,employment_status,reason)
                VALUES(?,? ,?,'REGULAR','ACTIVE','INITIAL_HIRE')
                """,user.tenantId(),employee.id(),input.hireDate());
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'EMPLOYEE_CREATED','personnel_employee',?)
                """,user.tenantId(),user.id(),employee.id());
            return employee;
        });
    }
    private static Employee row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Employee(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),
            rs.getString("employee_number"),rs.getString("first_name"),rs.getString("last_name"),
            rs.getObject("hire_date",LocalDate.class),rs.getString("employment_status"));
    }
}
