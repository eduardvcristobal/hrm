package com.apexmedsuite.hrm.personnel;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/personnel/departments")
public class DepartmentController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public DepartmentController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc; this.tenants=tenants;
    }
    public record Department(UUID id,UUID companyId,String code,String name,UUID parentId,boolean active) {}
    public record CreateDepartment(
        @NotNull UUID companyId,
        @NotBlank @Size(max=40) @Pattern(regexp="[A-Za-z0-9_-]+") String code,
        @NotBlank @Size(max=160) String name,
        UUID parentId
    ) {}
    @GetMapping
    public List<Department> list(@AuthenticationPrincipal HrmUserPrincipal user,@RequestParam UUID companyId) {
        return tenants.inTenant(user.tenantId(),()->jdbc.query("""
            SELECT id,company_id,department_code,department_name,parent_id,active
            FROM personnel_department WHERE tenant_id=? AND company_id=?
            ORDER BY department_code LIMIT 200
            """,(rs,i)->map(rs),user.tenantId(),companyId));
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Department create(@AuthenticationPrincipal HrmUserPrincipal user,@Valid @RequestBody CreateDepartment input) {
        return tenants.inTenant(user.tenantId(),()->{
            Integer companies=jdbc.queryForObject("SELECT count(*) FROM company WHERE tenant_id=? AND id=?",
                Integer.class,user.tenantId(),input.companyId());
            if(companies==null||companies==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Company not found");
            if(input.parentId()!=null) {
                Integer parents=jdbc.queryForObject("""
                    SELECT count(*) FROM personnel_department
                    WHERE tenant_id=? AND company_id=? AND id=? AND active
                    """,Integer.class,user.tenantId(),input.companyId(),input.parentId());
                if(parents==null||parents==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid parent department");
            }
            Department result=jdbc.queryForObject("""
                INSERT INTO personnel_department(tenant_id,company_id,department_code,department_name,parent_id)
                VALUES(?,?,?,?,?)
                RETURNING id,company_id,department_code,department_name,parent_id,active
                """,(rs,i)->map(rs),user.tenantId(),input.companyId(),input.code(),input.name().trim(),input.parentId());
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'DEPARTMENT_CREATED','personnel_department',?)
                """,user.tenantId(),user.id(),result.id());
            return result;
        });
    }
    @PatchMapping("/{id}/status")
    public Department setStatus(@AuthenticationPrincipal HrmUserPrincipal user,
                    @PathVariable UUID id,@RequestBody StatusInput input) {
        return tenants.inTenant(user.tenantId(),()->{
            if(!input.active()) {
                Integer children=jdbc.queryForObject("""
                    SELECT count(*) FROM personnel_department
                    WHERE tenant_id=? AND parent_id=? AND active
                    """,Integer.class,user.tenantId(),id);
                Integer assigned=jdbc.queryForObject("""
                    SELECT count(*) FROM personnel_employee
                    WHERE tenant_id=? AND department_id=? AND employment_status IN ('ACTIVE','ON_LEAVE')
                    """,Integer.class,user.tenantId(),id);
                if((children!=null&&children>0)||(assigned!=null&&assigned>0))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Department still has active dependents");
            }
            List<Department> found=jdbc.query("""
                UPDATE personnel_department SET active=? WHERE tenant_id=? AND id=?
                RETURNING id,company_id,department_code,department_name,parent_id,active
                """,(rs,i)->map(rs),input.active(),user.tenantId(),id);
            if(found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Department not found");
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'DEPARTMENT_STATUS_CHANGED','personnel_department',?)
                """,user.tenantId(),user.id(),id);
            return found.get(0);
        });
    }
    public record StatusInput(boolean active) {}
    private static Department map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Department(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),
            rs.getString("department_code"),rs.getString("department_name"),
            rs.getObject("parent_id",UUID.class),rs.getBoolean("active"));
    }
}
