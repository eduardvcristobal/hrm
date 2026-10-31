package com.apexmedsuite.hrm.timekeeping;

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

/** Employee row locking serializes competing schedule changes and prevents overlapping ranges. */
@RestController
@RequestMapping("/api/v1/timekeeping/employees/{employeeId}/schedules")
public class ScheduleController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public ScheduleController(JdbcTemplate jdbc,TenantJdbcExecutor tenants){this.jdbc=jdbc;this.tenants=tenants;}
    public record Assignment(UUID id,UUID employeeId,UUID shiftId,LocalDate from,LocalDate until,int weekdaysMask) {}
    public record NewAssignment(@NotNull UUID shiftId,@NotNull LocalDate effectiveFrom,
       LocalDate effectiveUntil,@Min(1) @Max(127) int weekdaysMask) {}
    @GetMapping
    public List<Assignment> list(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId){
        return tenants.inTenant(user.tenantId(),()->{
            ensureEmployee(user.tenantId(),employeeId,false);
            return jdbc.query("""
                SELECT id,employee_id,shift_id,effective_from,effective_until,weekdays_mask
                FROM time_schedule_assignment WHERE tenant_id=? AND employee_id=?
                ORDER BY effective_from DESC LIMIT 250
                """,(rs,n)->map(rs),user.tenantId(),employeeId);
        });
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Assignment assign(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                             @Valid @RequestBody NewAssignment input){
        if(input.effectiveUntil()!=null&&input.effectiveUntil().isBefore(input.effectiveFrom()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"End date precedes start");
        return tenants.inTenant(user.tenantId(),()->{
            UUID company=ensureEmployee(user.tenantId(),employeeId,true);
            Integer shift=jdbc.queryForObject("""
                SELECT count(*) FROM time_shift_template
                WHERE tenant_id=? AND id=? AND company_id=? AND active=true
                """,Integer.class,user.tenantId(),input.shiftId(),company);
            if(shift==null||shift!=1)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Active shift in employee company not found");
            Integer conflict=jdbc.queryForObject("""
                SELECT count(*) FROM time_schedule_assignment
                WHERE tenant_id=? AND employee_id=?
                  AND effective_from<=coalesce(?::date,'infinity'::date)
                  AND coalesce(effective_until,'infinity'::date)>=?
                  AND (weekdays_mask & ?)<>0
                """,Integer.class,user.tenantId(),employeeId,
                   input.effectiveUntil(),input.effectiveFrom(),input.weekdaysMask());
            if(conflict!=null&&conflict>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Overlapping schedule weekdays");
            Assignment result=jdbc.queryForObject("""
                INSERT INTO time_schedule_assignment
                 (tenant_id,employee_id,shift_id,effective_from,effective_until,weekdays_mask,created_by)
                VALUES(?,?,?,?,?,?,?)
                RETURNING id,employee_id,shift_id,effective_from,effective_until,weekdays_mask
                """,(rs,n)->map(rs),user.tenantId(),employeeId,input.shiftId(),
                    input.effectiveFrom(),input.effectiveUntil(),input.weekdaysMask(),user.id());
            jdbc.update("""
                INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
                VALUES(?,?,'TIME_SCHEDULE_ASSIGNED','time_schedule_assignment',?)
                """,user.tenantId(),user.id(),result.id());
            return result;
        });
    }
    private UUID ensureEmployee(UUID tenant,UUID id,boolean lock){
        List<UUID> companies=jdbc.query("""
            SELECT company_id FROM personnel_employee WHERE tenant_id=? AND id=?
            """+(lock?" FOR UPDATE":""),(rs,n)->rs.getObject(1,UUID.class),tenant,id);
        if(companies.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
        return companies.get(0);
    }
    private static Assignment map(java.sql.ResultSet rs)throws java.sql.SQLException {
        return new Assignment(rs.getObject("id",UUID.class),rs.getObject("employee_id",UUID.class),
            rs.getObject("shift_id",UUID.class),rs.getObject("effective_from",LocalDate.class),
            rs.getObject("effective_until",LocalDate.class),rs.getInt("weekdays_mask"));
    }
}
