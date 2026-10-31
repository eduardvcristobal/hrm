package com.apexmedsuite.hrm.timekeeping;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * M2.7 internal source reconciliation only. Never computes payable hours,
 * wages, premiums, statutory benefits, or transmits a payroll export.
 * Review is a second-actor acknowledgment of unchanged source evidence,
 * explicitly NOT approval to run payroll.
 */
@RestController
@RequestMapping("/api/v1/timekeeping/employees/{employeeId}/payroll-reconciliation")
public class PayrollReconciliationController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public PayrollReconciliationController(JdbcTemplate jdbc,TenantJdbcExecutor tenants){
        this.jdbc=jdbc; this.tenants=tenants;
    }
    public record Summary(int attendanceSessions,long rawClosedMinutes,int openSessions,
        int pendingCorrections,int pendingLeaveRequests,int approvedLeaveRequests,
        int unconsumedApprovedLeave,int calendarEvents,List<String> blockerCodes,
        String sourceFingerprint) {}
    public record Preview(UUID employeeId,LocalDate from,LocalDate to,String timezone,
                          Summary summary,boolean payrollReady,String classification) {}
    public record Package(UUID id,UUID employeeId,LocalDate from,LocalDate to,String timezone,
                          String status,Summary summary,UUID createdBy,UUID reviewedBy,
                          boolean payrollReady,String classification) {}
    private record Evidence(Summary summary,String source) {}
    private record Punch(UUID id,Instant in,Instant out) {}

    @GetMapping("/preview")
    public Preview preview(@AuthenticationPrincipal HrmUserPrincipal actor,
                           @PathVariable UUID employeeId,@RequestParam LocalDate from,
                           @RequestParam LocalDate to,@RequestParam(defaultValue="Asia/Manila") String timezone){
        ZoneId zone=validate(from,to,timezone);
        return tenants.inTenant(actor.tenantId(),()->{
            assertEmployee(actor.tenantId(),employeeId,false);
            Evidence source=collect(actor.tenantId(),employeeId,from,to,zone);
            return new Preview(employeeId,from,to,zone.getId(),source.summary(),false,
                "INTERNAL_RECONCILIATION_NOT_PAYROLL_APPROVED");
        });
    }
    @PostMapping("/packages")
    @ResponseStatus(HttpStatus.CREATED)
    public Package prepare(@AuthenticationPrincipal HrmUserPrincipal actor,
                           @PathVariable UUID employeeId,@RequestParam LocalDate from,
                           @RequestParam LocalDate to,@RequestParam(defaultValue="Asia/Manila") String timezone){
        ZoneId zone=validate(from,to,timezone);
        return tenants.inTenant(actor.tenantId(),()->{
            assertEmployee(actor.tenantId(),employeeId,true);
            Integer existing=jdbc.queryForObject("""
                SELECT count(*) FROM time_payroll_reconciliation
                WHERE tenant_id=? AND employee_id=? AND period_from=? AND period_to=?
                """,Integer.class,actor.tenantId(),employeeId,from,to);
            if(existing!=null&&existing>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"A package already exists for this employee and period");
            Evidence source=collect(actor.tenantId(),employeeId,from,to,zone);
            Summary x=source.summary();
            UUID id=jdbc.queryForObject("""
                INSERT INTO time_payroll_reconciliation
                 (tenant_id,employee_id,period_from,period_to,timezone,attendance_sessions,
                  raw_closed_minutes,open_sessions,pending_corrections,pending_leave_requests,
                  approved_leave_requests,unconsumed_approved_leave,calendar_events,
                  blocker_codes,source_fingerprint,source_snapshot,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
                """,UUID.class,actor.tenantId(),employeeId,from,to,zone.getId(),
                x.attendanceSessions(),x.rawClosedMinutes(),x.openSessions(),
                x.pendingCorrections(),x.pendingLeaveRequests(),x.approvedLeaveRequests(),
                x.unconsumedApprovedLeave(),x.calendarEvents(),
                x.blockerCodes().toArray(String[]::new),x.sourceFingerprint(),
                source.source(),actor.id());
            audit(actor,"TIME_RECONCILIATION_PREPARED",id);
            return new Package(id,employeeId,from,to,zone.getId(),"DRAFT",x,
                actor.id(),null,false,"INTERNAL_RECONCILIATION_NOT_PAYROLL_APPROVED");
        });
    }
    @GetMapping("/packages")
    public List<Package> packages(@AuthenticationPrincipal HrmUserPrincipal actor,
                                  @PathVariable UUID employeeId){
        return tenants.inTenant(actor.tenantId(),()->{
            assertEmployee(actor.tenantId(),employeeId,false);
            return jdbc.query("""
                SELECT * FROM time_payroll_reconciliation
                WHERE tenant_id=? AND employee_id=? ORDER BY created_at DESC LIMIT 100
                """,(rs,n)->map(rs),actor.tenantId(),employeeId);
        });
    }
    @PostMapping("/packages/{packageId}/review")
    public Package review(@AuthenticationPrincipal HrmUserPrincipal actor,
                          @PathVariable UUID employeeId,@PathVariable UUID packageId){
        return tenants.inTenant(actor.tenantId(),()->{
            assertEmployee(actor.tenantId(),employeeId,true);
            List<Package> rows=jdbc.query("""
                SELECT * FROM time_payroll_reconciliation WHERE tenant_id=?
                AND employee_id=? AND id=? AND status='DRAFT' FOR UPDATE
                """,(rs,n)->map(rs),actor.tenantId(),employeeId,packageId);
            if(rows.isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Draft reconciliation not found");
            Package stored=rows.get(0);
            if(stored.createdBy().equals(actor.id()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Independent reviewer required");
            if(!stored.summary().blockerCodes().isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Resolve reconciliation blockers first");
            ZoneId zone=ZoneId.of(stored.timezone());
            Evidence current=collect(actor.tenantId(),employeeId,stored.from(),stored.to(),zone);
            if(!current.summary().sourceFingerprint().equals(stored.summary().sourceFingerprint()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Source data changed; package cannot be reviewed");
            int changed=jdbc.update("""
                UPDATE time_payroll_reconciliation
                SET status='INTERNALLY_REVIEWED',reviewed_by=?,reviewed_at=now()
                WHERE tenant_id=? AND employee_id=? AND id=? AND status='DRAFT'
                """,actor.id(),actor.tenantId(),employeeId,packageId);
            if(changed!=1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Reconciliation already reviewed");
            audit(actor,"TIME_RECONCILIATION_INTERNAL_REVIEW",packageId);
            return new Package(stored.id(),employeeId,stored.from(),stored.to(),
                stored.timezone(),"INTERNALLY_REVIEWED",stored.summary(),
                stored.createdBy(),actor.id(),false,
                "INTERNAL_RECONCILIATION_NOT_PAYROLL_APPROVED");
        });
    }
    private Evidence collect(UUID tenant,UUID employee,LocalDate from,LocalDate to,ZoneId zone){
        Instant start=from.atStartOfDay(zone).toInstant();
        Instant end=to.plusDays(1).atStartOfDay(zone).toInstant();
        List<Punch> punches=jdbc.query("""
            SELECT id,clock_in_at,clock_out_at FROM time_attendance_session
            WHERE tenant_id=? AND employee_id=? AND clock_in_at<?
              AND (clock_out_at IS NULL OR clock_out_at>?)
            ORDER BY clock_in_at,id LIMIT 501
            """,(rs,n)->new Punch(rs.getObject(1,UUID.class),rs.getTimestamp(2).toInstant(),
                rs.getTimestamp(3)==null?null:rs.getTimestamp(3).toInstant()),
            tenant,employee,Timestamp.from(end),Timestamp.from(start));
        if(punches.size()>500)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Attendance source exceeds 500 sessions");
        StringBuilder sources=new StringBuilder("M2.7|").append(employee).append('|')
            .append(from).append('|').append(to).append('|').append(zone.getId()).append('|');
        long minutes=0;
        int open=0;
        for(Punch p:punches){
            sources.append(p.id()).append(':').append(p.in()).append(':').append(p.out()).append(';');
            if(p.out()==null){open++;continue;}
            Instant segmentStart=p.in().isAfter(start)?p.in():start;
            Instant segmentEnd=p.out().isBefore(end)?p.out():end;
            if(segmentEnd.isAfter(segmentStart))
                minutes+=Duration.between(segmentStart,segmentEnd).toMinutes();
        }
        int pendingCorrections=sourceRows(sources,"CORRECTIONS", """
            SELECT c.id::text||':'||c.status||':'||c.new_clock_in||':'||c.new_clock_out
            FROM time_attendance_correction c
            JOIN time_attendance_session s ON s.tenant_id=c.tenant_id AND s.id=c.session_id
            WHERE c.tenant_id=? AND c.employee_id=? AND s.clock_in_at<?
              AND s.clock_out_at>? ORDER BY c.id
            """,tenant,employee,Timestamp.from(end),Timestamp.from(start));
        int pendingCorrectionCount=jdbc.queryForObject("""
            SELECT count(*) FROM time_attendance_correction c
            JOIN time_attendance_session s ON s.tenant_id=c.tenant_id AND s.id=c.session_id
            WHERE c.tenant_id=? AND c.employee_id=? AND c.status='PENDING'
              AND s.clock_in_at<? AND s.clock_out_at>?
            """,Integer.class,tenant,employee,Timestamp.from(end),Timestamp.from(start));
        sourceRows(sources,"LEAVE", """
            SELECT id::text||':'||status||':'||starts_on||':'||ends_on
            FROM time_leave_request WHERE tenant_id=? AND employee_id=?
              AND starts_on<=? AND ends_on>=? ORDER BY id
            """,tenant,employee,to,from);
        int pendingLeave=jdbc.queryForObject("""
            SELECT count(*) FROM time_leave_request
            WHERE tenant_id=? AND employee_id=? AND status='PENDING'
              AND starts_on<=? AND ends_on>=?
            """,Integer.class,tenant,employee,to,from);
        int approvedLeave=jdbc.queryForObject("""
            SELECT count(*) FROM time_leave_request
            WHERE tenant_id=? AND employee_id=? AND status='APPROVED'
              AND starts_on<=? AND ends_on>=?
            """,Integer.class,tenant,employee,to,from);
        int unconsumed=jdbc.queryForObject("""
            SELECT count(*) FROM time_leave_request l
            WHERE l.tenant_id=? AND l.employee_id=? AND l.status='APPROVED'
              AND l.starts_on<=? AND l.ends_on>=?
              AND NOT EXISTS (
                 SELECT 1 FROM time_leave_balance_event e
                 WHERE e.tenant_id=l.tenant_id AND e.leave_request_id=l.id
                   AND e.event_type='DEBIT'
                   AND NOT EXISTS(
                     SELECT 1 FROM time_leave_balance_event r WHERE r.tenant_id=e.tenant_id
                     AND r.reverses_event_id=e.id AND r.event_type='REVERSAL'))
            """,Integer.class,tenant,employee,to,from);
        sourceRows(sources,"BALANCE", """
            SELECT id::text||':'||event_type||':'||days FROM time_leave_balance_event
            WHERE tenant_id=? AND employee_id=? ORDER BY id
            """,tenant,employee);
        UUID company=jdbc.queryForObject("""
            SELECT company_id FROM personnel_employee WHERE tenant_id=? AND id=?
            """,UUID.class,tenant,employee);
        int calendarEvents=sourceRows(sources,"CALENDAR", """
            SELECT id::text||':'||day_kind||':'||calendar_date FROM time_company_calendar_day
            WHERE tenant_id=? AND company_id=? AND calendar_date BETWEEN ? AND ? ORDER BY id
            """,tenant,company,from,to);
        sourceRows(sources,"OVERRIDES", """
            SELECT id::text||':'||day_kind||':'||calendar_date FROM time_employee_day_override
            WHERE tenant_id=? AND employee_id=? AND calendar_date BETWEEN ? AND ? ORDER BY id
            """,tenant,employee,from,to);
        sourceRows(sources,"SCHEDULE", """
            SELECT id::text||':'||shift_id||':'||weekdays_mask||':'||effective_from||':'||effective_until
            FROM time_schedule_assignment WHERE tenant_id=? AND employee_id=?
             AND effective_from<=? AND (effective_until IS NULL OR effective_until>=?)
            ORDER BY id
            """,tenant,employee,to,from);
        List<String> blockers=new ArrayList<>();
        if(open>0)blockers.add("OPEN_ATTENDANCE");
        if(pendingCorrectionCount>0)blockers.add("PENDING_ATTENDANCE_CORRECTION");
        if(pendingLeave>0)blockers.add("PENDING_LEAVE_REVIEW");
        if(unconsumed>0)blockers.add("UNRECONCILED_APPROVED_LEAVE");
        // The clock sessions are raw; all employer holiday classifications
        // remain provisional and are not automatically converted to wages.
        if(calendarEvents>0)blockers.add("CALENDAR_POLICY_UNCERTIFIED");
        Summary summary=new Summary(punches.size(),minutes,open,
            pendingCorrectionCount,pendingLeave,approvedLeave,unconsumed,
            calendarEvents,List.copyOf(blockers),sha256(sources.toString()));
        return new Evidence(summary,sources.toString());
    }
    private int sourceRows(StringBuilder out,String label,String sql,Object...args){
        List<String> rows=jdbc.query(sql,(rs,n)->rs.getString(1),args);
        if(rows.size()>500)throw new ResponseStatusException(HttpStatus.CONFLICT,"Source set exceeds 500 rows");
        out.append('|').append(label).append('[');
        rows.forEach(x->out.append(x).append(';'));
        out.append(']');
        return rows.size();
    }
    private static String sha256(String source){
        try{
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    private static ZoneId validate(LocalDate from,LocalDate to,String timezone){
        if(to.isBefore(from)||to.isAfter(from.plusDays(30)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Date range limited to 31 days");
        try{return ZoneId.of(timezone);}
        catch(DateTimeException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown timezone");}
    }
    private void assertEmployee(UUID tenant,UUID employee,boolean lock){
        List<UUID> rows=jdbc.query("""
            SELECT id FROM personnel_employee WHERE tenant_id=? AND id=?
            """+(lock?" FOR UPDATE":""),(rs,n)->rs.getObject(1,UUID.class),tenant,employee);
        if(rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
    }
    private static Package map(java.sql.ResultSet rs)throws java.sql.SQLException{
        String[] codes=(String[])rs.getArray("blocker_codes").getArray();
        Summary summary=new Summary(rs.getInt("attendance_sessions"),
          rs.getLong("raw_closed_minutes"),rs.getInt("open_sessions"),
          rs.getInt("pending_corrections"),rs.getInt("pending_leave_requests"),
          rs.getInt("approved_leave_requests"),rs.getInt("unconsumed_approved_leave"),
          rs.getInt("calendar_events"),List.of(codes),rs.getString("source_fingerprint"));
        return new Package(rs.getObject("id",UUID.class),rs.getObject("employee_id",UUID.class),
            rs.getObject("period_from",LocalDate.class),rs.getObject("period_to",LocalDate.class),
            rs.getString("timezone"),rs.getString("status"),summary,
            rs.getObject("created_by",UUID.class),rs.getObject("reviewed_by",UUID.class),
            false,"INTERNAL_RECONCILIATION_NOT_PAYROLL_APPROVED");
    }
    private void audit(HrmUserPrincipal actor,String action,UUID id){
        jdbc.update("""
            INSERT INTO audit_event(tenant_id,actor_id,action,entity_type,entity_id)
            VALUES(?,?,?,'time_payroll_reconciliation',?)
            """,actor.tenantId(),actor.id(),action,id);
    }
}
