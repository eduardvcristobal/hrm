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
@RequestMapping("/api/v1/personnel/employees/{employeeId}/assignments")
public class AssignmentController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public AssignmentController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record Assignment(UUID id,UUID companyId,UUID branchId,UUID departmentId,UUID positionId,
                              LocalDate effectiveDate,String reason) {}
    public record NewAssignment(@NotNull UUID companyId,UUID branchId,UUID departmentId,UUID positionId,
                                 @NotNull @PastOrPresent LocalDate effectiveDate,
                                 @NotBlank @Size(max=250) String reason) {}
    @GetMapping
    public List<Assignment> list(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId) {
        return tenants.inTenant(user.tenantId(),()->{
            ensureEmployee(user.tenantId(),employeeId);
            return jdbc.query("""
                SELECT id,company_id,branch_id,department_id,position_id,effective_date,reason
                FROM personnel_assignment_history WHERE tenant_id=? AND employee_id=?
                ORDER BY effective_date DESC LIMIT 100
                """,(rs,i)->row(rs),user.tenantId(),employeeId);
        });
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Assignment assign(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                              @Valid @RequestBody NewAssignment input) {
        return tenants.inTenant(user.tenantId(),()->{
            var employee=jdbc.query("""
                SELECT company_id,hire_date FROM personnel_employee
                WHERE tenant_id=? AND id=? FOR UPDATE
                """,(rs,i)->new EmployeeCompany(rs.getObject(1,UUID.class),
                    rs.getObject(2,LocalDate.class)),user.tenantId(),employeeId);
            if(employee.size()!=1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
            if(!employee.get(0).companyId().equals(input.companyId()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cross-company assignment prohibited");
            if(input.effectiveDate().isBefore(employee.get(0).hireDate()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Assignment predates hire");
            LocalDate last=jdbc.queryForObject("""
                SELECT max(effective_date) FROM personnel_assignment_history WHERE tenant_id=? AND employee_id=?
                """,LocalDate.class,user.tenantId(),employeeId);
            if(last!=null && !input.effectiveDate().isAfter(last))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Assignment must follow previous effective date");
            if(input.branchId()!=null) {
                Integer n=jdbc.queryForObject("""
                    SELECT count(*) FROM branch WHERE tenant_id=? AND company_id=? AND id=?
                    """,Integer.class,user.tenantId(),input.companyId(),input.branchId());
                if(n==null||n==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid branch");
            }
            if(input.positionId()!=null) {
                Integer n=jdbc.queryForObject("""
                    SELECT count(*) FROM personnel_position
                    WHERE tenant_id=? AND company_id=? AND id=? AND active
                    """,Integer.class,user.tenantId(),input.companyId(),input.positionId());
                if(n==null||n==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid position");
            }
            if(input.departmentId()!=null) {
                Integer n=jdbc.queryForObject("""
                    SELECT count(*) FROM personnel_department
                    WHERE tenant_id=? AND company_id=? AND id=? AND active
                    """,Integer.class,user.tenantId(),input.companyId(),input.departmentId());
                if(n==null||n==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid department");
            }
            Assignment result=jdbc.queryForObject("""
                INSERT INTO personnel_assignment_history
                  (tenant_id,employee_id,company_id,branch_id,department_id,position_id,effective_date,reason,recorded_by)
                VALUES(?,?,?,?,?,?,?,?,?)
                RETURNING id,company_id,branch_id,department_id,effective_date,reason
                """,(rs,i)->row(rs),user.tenantId(),employeeId,input.companyId(),
                input.branchId(),input.departmentId(),input.positionId(),input.effectiveDate(),input.reason().trim(),user.id());
            jdbc.update("""
                UPDATE personnel_employee SET branch_id=?,department_id=?,position_id=?,updated_at=now()
                WHERE tenant_id=? AND id=?
                """,input.branchId(),input.departmentId(),input.positionId(),user.tenantId(),employeeId);
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'EMPLOYEE_ASSIGNED','personnel_employee',?)
                """,user.tenantId(),user.id(),employeeId);
            return result;
        });
    }
    private void ensureEmployee(UUID tenantId,UUID employeeId) {
        Integer n=jdbc.queryForObject("SELECT count(*) FROM personnel_employee WHERE tenant_id=? AND id=?",
            Integer.class,tenantId,employeeId);
        if(n==null||n==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
    }
    private record EmployeeCompany(UUID companyId,LocalDate hireDate) {}
    private static Assignment row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Assignment(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),
            rs.getObject("branch_id",UUID.class),rs.getObject("department_id",UUID.class),
            rs.getObject("position_id",UUID.class),
            rs.getObject("effective_date",LocalDate.class),rs.getString("reason"));
    }
}
