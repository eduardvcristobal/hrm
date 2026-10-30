package com.apexmedsuite.hrm.timekeeping;

import com.apexmedsuite.hrm.platform.security.HrmUserPrincipal;
import com.apexmedsuite.hrm.platform.tenant.TenantJdbcExecutor;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** M2.3 raw completed duration totals; never a payable hours or overtime calculation. */
@RestController
@RequestMapping("/api/v1/timekeeping/employees/{employeeId}/summaries")
public class AttendanceSummaryController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public AttendanceSummaryController(JdbcTemplate jdbc,TenantJdbcExecutor tenants) {
        this.jdbc=jdbc;this.tenants=tenants;
    }
    public record DailySummary(LocalDate day,long completedMinutes,int openSessions) {}
    private record Punch(Instant start,Instant end) {}
    @GetMapping
    public List<DailySummary> days(@AuthenticationPrincipal HrmUserPrincipal user,@PathVariable UUID employeeId,
         @RequestParam LocalDate from,@RequestParam LocalDate to,
         @RequestParam(defaultValue="Asia/Manila") String timezone) {
        if(to.isBefore(from)||to.isAfter(from.plusDays(30)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Date range limited to 31 days");
        ZoneId zone;
        try{zone=ZoneId.of(timezone);}
        catch(DateTimeException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown timezone");}
        Instant lower=from.atStartOfDay(zone).toInstant();
        Instant upper=to.plusDays(1).atStartOfDay(zone).toInstant();
        return tenants.inTenant(user.tenantId(),()->{
            Integer count=jdbc.queryForObject("""
                SELECT count(*) FROM personnel_employee WHERE tenant_id=? AND id=?
                """,Integer.class,user.tenantId(),employeeId);
            if(count==null||count==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found");
            List<Punch> punches=jdbc.query("""
                SELECT clock_in_at,clock_out_at FROM time_attendance_session
                WHERE tenant_id=? AND employee_id=? AND clock_in_at>=? AND clock_in_at<?
                ORDER BY clock_in_at LIMIT 500
                """,(rs,n)->new Punch(rs.getTimestamp(1).toInstant(),
                    rs.getTimestamp(2)==null?null:rs.getTimestamp(2).toInstant()),
                    user.tenantId(),employeeId,Timestamp.from(lower),Timestamp.from(upper));
            Map<LocalDate,long[]> totals=new TreeMap<>();
            for(Punch p:punches){
                LocalDate day=p.start().atZone(zone).toLocalDate();
                long[] tally=totals.computeIfAbsent(day,k->new long[2]);
                if(p.end()==null)tally[1]++;
                else tally[0]+=Duration.between(p.start(),p.end()).toMinutes();
            }
            return totals.entrySet().stream()
                .map(e->new DailySummary(e.getKey(),e.getValue()[0],(int)e.getValue()[1]))
                .toList();
        });
    }
}
