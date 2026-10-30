package com.apexmedsuite.hrm.timekeeping;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** M2.2 initial controlled attendance capture: server time, admin actor, one open clock per employee. */
@RestController
@RequestMapping("/api/v1/timekeeping/employees/{employeeId}/attendance")
public class AttendanceController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public AttendanceController(JdbcTemplate jdbc,TenantJdbcExecutor tenants){this.jdbc=jdbc;this.tenants=tenants;}
    public record Session(UUID id,UUID employeeId,Instant clockInAt,Instant clockOutAt,
                          UUID clockInBy,UUID clockOutBy,String source) {}
    @GetMapping
    public List<Session> list(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId){
        return tenants.inTenant(user.tenantId(),()->{
            employeeStatus(user.tenantId(),employeeId,false);
            return jdbc.query("""
                SELECT id,employee_id,clock_in_at,clock_out_at,clock_in_by,clock_out_by,source
                FROM time_attendance_session WHERE tenant_id=? AND employee_id=?
                ORDER BY clock_in_at DESC LIMIT 250
                """,(rs,n)->map(rs),user.tenantId(),employeeId);
        });
    }
    @PostMapping("/clock-in")
    @ResponseStatus(HttpStatus.CREATED)
    public Session clockIn(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId){
        return tenants.inTenant(user.tenantId(),()->{
            String status=employeeStatus(user.tenantId(),employeeId,true);
            if(!"ACTIVE".equals(status))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Employee is not active");
            Integer open=jdbc.queryForObject("""
                SELECT count(*) FROM time_attendance_session WHERE tenant_id=? AND employee_id=? AND clock_out_at IS NULL
                """,Integer.class,user.tenantId(),employeeId);
            if(open!=null&&open>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Employee already clocked in");
            Session row=jdbc.queryForObject("""
                INSERT INTO time_attendance_session(tenant_id,employee_id,clock_in_by)
                VALUES(?,?,?)
                RETURNING id,employee_id,clock_in_at,clock_out_at,clock_in_by,clock_out_by,source
                """,(rs,n)->map(rs),user.tenantId(),employeeId,user.id());
            audit(user,"TIME_CLOCK_IN",row.id());
            return row;
        });
    }
    @PostMapping("/{sessionId}/clock-out")
    public Session clockOut(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
                            @PathVariable UUID sessionId){
        return tenants.inTenant(user.tenantId(),()->{
            employeeStatus(user.tenantId(),employeeId,true);
            List<Session> changed=jdbc.query("""
                UPDATE time_attendance_session SET clock_out_at=now(),clock_out_by=?
                WHERE tenant_id=? AND employee_id=? AND id=? AND clock_out_at IS NULL
                RETURNING id,employee_id,clock_in_at,clock_out_at,clock_in_by,clock_out_by,source
                """,(rs,n)->map(rs),user.id(),user.tenantId(),employeeId,sessionId);
            if(changed.isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Open attendance session not found");
            audit(user,"TIME_CLOCK_OUT",sessionId);
            return changed.get(0);
        });
    }
    private String employeeStatus(UUID tenant,UUID id,boolean lock) {
        List<String> status=jdbc.query("""
            SELECT employment_status FROM personnel_employee WHERE tenant_id=? AND id=?
            """+(lock?" FOR UPDATE":""),(rs,n)->rs.getString(1),tenant,id);
        if(status.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
        return status.get(0);
    }
    private void audit(HrmUserPrincipal user,String action,UUID id){
        jdbc.update("""
            INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
            VALUES(?,?,?,'time_attendance_session',?)
            """,user.tenantId(),user.id(),action,id);
    }
    private static Session map(java.sql.ResultSet rs)throws java.sql.SQLException {
        return new Session(rs.getObject("id",UUID.class),rs.getObject("employee_id",UUID.class),
            rs.getTimestamp("clock_in_at").toInstant(),
            rs.getTimestamp("clock_out_at")==null?null:rs.getTimestamp("clock_out_at").toInstant(),
            rs.getObject("clock_in_by",UUID.class),rs.getObject("clock_out_by",UUID.class),
            rs.getString("source"));
    }
}
