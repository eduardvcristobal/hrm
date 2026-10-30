package com.apexmedsuite.hrm.timekeeping;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Corrected UTC instants are applied only after a different MFA tenant admin approves. */
@RestController
@RequestMapping("/api/v1/timekeeping/employees/{employeeId}/attendance/{sessionId}/corrections")
public class AttendanceCorrectionController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public AttendanceCorrectionController(JdbcTemplate jdbc,TenantJdbcExecutor tenants){
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record Change(@NotNull Instant clockInAt,@NotNull Instant clockOutAt,
                         @NotBlank @Size(min=8,max=500) String reason){}
    public record Correction(UUID id,UUID sessionId,String status,UUID requestedBy,UUID reviewedBy,
                             Instant clockInAt,Instant clockOutAt){}
    private record Session(Instant start,Instant end){}
    @GetMapping
    public List<Correction> list(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
        @PathVariable UUID sessionId){
        return tenants.inTenant(user.tenantId(),()->{
            session(user.tenantId(),employeeId,sessionId,false);
            return jdbc.query("""
                SELECT id,session_id,status,requested_by,reviewed_by,new_clock_in,new_clock_out
                FROM time_attendance_correction WHERE tenant_id=? AND employee_id=? AND session_id=?
                ORDER BY requested_at DESC LIMIT 100
                """,(rs,n)->row(rs),user.tenantId(),employeeId,sessionId);
        });
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Correction request(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
        @PathVariable UUID sessionId,@Valid @RequestBody Change input){
        validate(input.clockInAt(),input.clockOutAt());
        return tenants.inTenant(user.tenantId(),()->{
            Session old=session(user.tenantId(),employeeId,sessionId,true);
            if(old.end()==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Close session before correction");
            Correction result=jdbc.queryForObject("""
                INSERT INTO time_attendance_correction
                 (tenant_id,employee_id,session_id,old_clock_in,old_clock_out,new_clock_in,new_clock_out,reason,requested_by)
                VALUES(?,?,?,?,?,?,?,?,?)
                RETURNING id,session_id,status,requested_by,reviewed_by,new_clock_in,new_clock_out
                """,(rs,n)->row(rs),user.tenantId(),employeeId,sessionId,old.start(),old.end(),
                    input.clockInAt(),input.clockOutAt(),input.reason().trim(),user.id());
            audit(user,"TIME_CORRECTION_REQUESTED",result.id());
            return result;
        });
    }
    @PostMapping("/{correctionId}/decision")
    public Correction decide(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
        @PathVariable UUID sessionId,@PathVariable UUID correctionId,
        @RequestParam @Pattern(regexp="APPROVED|REJECTED") String decision){
        return tenants.inTenant(user.tenantId(),()->{
            Session current=session(user.tenantId(),employeeId,sessionId,true);
            List<Decision> pending=jdbc.query("""
                SELECT old_clock_in,old_clock_out,new_clock_in,new_clock_out,requested_by
                FROM time_attendance_correction
                WHERE tenant_id=? AND employee_id=? AND session_id=? AND id=? AND status='PENDING'
                FOR UPDATE
                """,(rs,n)->new Decision(rs.getTimestamp(1).toInstant(),rs.getTimestamp(2).toInstant(),
                    rs.getTimestamp(3).toInstant(),rs.getTimestamp(4).toInstant(),
                    rs.getObject(5,UUID.class)),user.tenantId(),employeeId,sessionId,correctionId);
            if(pending.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Pending correction not found");
            Decision row=pending.get(0);
            if(row.requestedBy().equals(user.id()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Independent review required");
            if("APPROVED".equals(decision)){
                if(!row.oldIn().equals(current.start())||!Objects.equals(row.oldOut(),current.end()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Attendance changed after correction request");
                validate(row.newIn(),row.newOut());
                jdbc.update("""
                    UPDATE time_attendance_session SET clock_in_at=?,clock_out_at=?
                    WHERE tenant_id=? AND employee_id=? AND id=? AND clock_out_at IS NOT NULL
                    """,row.newIn(),row.newOut(),user.tenantId(),employeeId,sessionId);
            }
            Correction result=jdbc.queryForObject("""
                UPDATE time_attendance_correction SET status=?,reviewed_by=?,reviewed_at=now()
                WHERE tenant_id=? AND employee_id=? AND session_id=? AND id=? AND status='PENDING'
                RETURNING id,session_id,status,requested_by,reviewed_by,new_clock_in,new_clock_out
                """,(rs,n)->row(rs),decision,user.id(),user.tenantId(),employeeId,sessionId,correctionId);
            audit(user,"TIME_CORRECTION_"+decision,correctionId);
            return result;
        });
    }
    private record Decision(Instant oldIn,Instant oldOut,Instant newIn,Instant newOut,UUID requestedBy){}
    private void validate(Instant start,Instant end){
        if(end.isBefore(start)||Duration.between(start,end).compareTo(Duration.ofHours(24))>0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Correction must be a valid session of at most 24 hours");
    }
    private Session session(UUID tenant,UUID employee,UUID session,boolean lock){
        List<Session> records=jdbc.query("""
            SELECT clock_in_at,clock_out_at FROM time_attendance_session
            WHERE tenant_id=? AND employee_id=? AND id=?
            """+(lock?" FOR UPDATE":""),(rs,n)->new Session(rs.getTimestamp(1).toInstant(),
                    rs.getTimestamp(2)==null?null:rs.getTimestamp(2).toInstant()),tenant,employee,session);
        if(records.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Attendance session not found");
        return records.get(0);
    }
    private void audit(HrmUserPrincipal user,String action,UUID id) {
        jdbc.update("""
            INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
            VALUES(?,?,?,'time_attendance_correction',?)
            """,user.tenantId(),user.id(),action,id);
    }
    private static Correction row(java.sql.ResultSet rs)throws java.sql.SQLException{
        return new Correction(rs.getObject("id",UUID.class),rs.getObject("session_id",UUID.class),
            rs.getString("status"),rs.getObject("requested_by",UUID.class),rs.getObject("reviewed_by",UUID.class),
            rs.getTimestamp("new_clock_in").toInstant(),rs.getTimestamp("new_clock_out").toInstant());
    }
}
