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
    public record Reparent(UUID parentId) {}
    @PatchMapping("/{id}/parent")
    public Department reparent(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID id,
                               @RequestBody Reparent input) {
        return tenants.inTenant(user.tenantId(),()->{
            jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))",(rs,n)->rs.getObject(1),
                "personnel-department-tree:"+user.tenantId());
            List<Department> current=jdbc.query("""
                SELECT id,company_id,department_code,department_name,parent_id,active
                FROM personnel_department WHERE tenant_id=? AND id=? FOR UPDATE
                """,(rs,n)->map(rs),user.tenantId(),id);
            if(current.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Department not found");
            Department existing=current.get(0);
            if(!existing.active()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Inactive department");
            if(input.parentId()!=null) {
                if(id.equals(input.parentId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Self parent");
                Integer valid=jdbc.queryForObject("""
                    SELECT count(*) FROM personnel_department
                    WHERE tenant_id=? AND company_id=? AND id=? AND active
                    """,Integer.class,user.tenantId(),existing.companyId(),input.parentId());
                if(valid==null||valid==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid parent");
                Integer cycle=jdbc.queryForObject("""
                    WITH RECURSIVE descendants(id) AS (
                      SELECT id FROM personnel_department WHERE tenant_id=? AND company_id=? AND id=?
                      UNION
                      SELECT child.id FROM personnel_department child JOIN descendants d ON child.parent_id=d.id
                      WHERE child.tenant_id=? AND child.company_id=?
                    ) SELECT count(*) FROM descendants WHERE id=?
                    """,Integer.class,user.tenantId(),existing.companyId(),id,
                    user.tenantId(),existing.companyId(),input.parentId());
                if(cycle!=null&&cycle>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Department cycle");
            }
            Department result=jdbc.queryForObject("""
                UPDATE personnel_department SET parent_id=? WHERE tenant_id=? AND id=?
                RETURNING id,company_id,department_code,department_name,parent_id,active
                """,(rs,n)->map(rs),input.parentId(),user.tenantId(),id);
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'DEPARTMENT_REPARENTED','personnel_department',?)
                """,user.tenantId(),user.id(),id);
            return result;
        });
    }
    public record StatusInput(boolean active) {}
    private static Department map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Department(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),
            rs.getString("department_code"),rs.getString("department_name"),
            rs.getObject("parent_id",UUID.class),rs.getBoolean("active"));
    }
}
