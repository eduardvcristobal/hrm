package com.apexmedsuite.hrm.personnel;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/personnel/employees/{employeeId}/supervisor")
public class SupervisorController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public SupervisorController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record Supervisor(UUID employeeId,UUID supervisorId) {}
    public record UpdateSupervisor(UUID supervisorId) {}
    @PutMapping
    public Supervisor update(@AuthenticationPrincipal HrmUserPrincipal user,
                             @PathVariable UUID employeeId,@RequestBody UpdateSupervisor input) {
        return tenants.inTenant(user.tenantId(),()->{
            // Serialize changes to reporting lines within a tenant to prevent concurrent cycles.
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))",Long.class,
                    "personnel-reporting:"+user.tenantId());
            List<UUID> companies=jdbc.query("""
                SELECT company_id FROM personnel_employee WHERE tenant_id=? AND id=? FOR UPDATE
                """,(rs,n)->rs.getObject(1,UUID.class),user.tenantId(),employeeId);
            if(companies.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
            if(input.supervisorId()!=null) {
                if(employeeId.equals(input.supervisorId()))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Self reporting prohibited");
                Integer valid=jdbc.queryForObject("""
                    SELECT count(*) FROM personnel_employee
                    WHERE tenant_id=? AND company_id=? AND id=? AND employment_status='ACTIVE'
                    """,Integer.class,user.tenantId(),companies.get(0),input.supervisorId());
                if(valid==null||valid==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid supervisor");
                Integer cycle=jdbc.queryForObject("""
                    WITH RECURSIVE chain(id,supervisor_id) AS (
                      SELECT id,supervisor_id FROM personnel_employee
                      WHERE tenant_id=? AND company_id=? AND id=?
                      UNION
                      SELECT e.id,e.supervisor_id FROM personnel_employee e
                      JOIN chain c ON e.id=c.supervisor_id
                      WHERE e.tenant_id=? AND e.company_id=?
                    )
                    SELECT count(*) FROM chain WHERE id=?
                    """,Integer.class,user.tenantId(),companies.get(0),input.supervisorId(),
                      user.tenantId(),companies.get(0),employeeId);
                if(cycle!=null&&cycle>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Reporting cycle prohibited");
            }
            jdbc.update("UPDATE personnel_employee SET supervisor_id=?,updated_at=now() WHERE tenant_id=? AND id=?",
                input.supervisorId(),user.tenantId(),employeeId);
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'SUPERVISOR_CHANGED','personnel_employee',?)
                """,user.tenantId(),user.id(),employeeId);
            return new Supervisor(employeeId,input.supervisorId());
        });
    }
}
