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

/**
 * M2.3 informational attendance-vs-schedule preview.
 * Does NOT calculate wages, payable hours, approved OT or statutory premiums.
 * SecurityConfig requires MFA-verified tenant admin; TenantJdbcExecutor scopes RLS.
 */
@RestController
@RequestMapping("/api/v1/timekeeping/employees/{employeeId}/variance-preview")
public class AttendanceVarianceController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenants;
    public AttendanceVarianceController(JdbcTemplate jdbc, TenantJdbcExecutor tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    private record Shift(UUID id, LocalTime start, LocalTime end, ZoneId zone,
                         int grace, int unpaidBreak) {}
    private record Session(Instant start, Instant end) {}
    public record Preview(LocalDate scheduleDate, UUID shiftId, String timezone,
                          AttendanceVarianceCalculator.Result variance,
                          boolean provisional, String classification) {}

    @GetMapping
    public List<Preview> preview(@AuthenticationPrincipal HrmUserPrincipal user,
                                 @PathVariable UUID employeeId,
                                 @RequestParam LocalDate from,
                                 @RequestParam LocalDate to) {
        if (to.isBefore(from) || to.isAfter(from.plusDays(30)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date range limited to 31 days");
        return tenants.inTenant(user.tenantId(), () -> {
            Integer employee = jdbc.queryForObject("""
                SELECT count(*) FROM personnel_employee WHERE tenant_id=? AND id=?
                """, Integer.class, user.tenantId(), employeeId);
            if (employee == null || employee == 0)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found");

            List<Preview> results = new ArrayList<>();
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                // bit0=Monday, bit6=Sunday, as defined by V36.
                int weekday = 1 << (date.getDayOfWeek().getValue() - 1);
                List<Shift> shifts = jdbc.query("""
                    SELECT s.id,s.starts_at,s.ends_at,s.timezone,
                           s.grace_minutes,s.unpaid_break_minutes
                    FROM time_schedule_assignment a
                    JOIN time_shift_template s ON s.tenant_id=a.tenant_id AND s.id=a.shift_id
                    WHERE a.tenant_id=? AND a.employee_id=?
                      AND a.effective_from<=? AND (a.effective_until IS NULL OR a.effective_until>=?)
                      AND (a.weekdays_mask & ?)<>0
                    ORDER BY a.created_at DESC LIMIT 2
                    """, (rs, n) -> new Shift(rs.getObject(1, UUID.class),
                        rs.getObject(2, LocalTime.class), rs.getObject(3, LocalTime.class),
                        ZoneId.of(rs.getString(4)), rs.getInt(5), rs.getInt(6)),
                    user.tenantId(), employeeId, date, date, weekday);
                if (shifts.size()>1)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Ambiguous employee schedule");
                if (shifts.isEmpty()) continue;
                Shift shift = shifts.get(0);
                Instant start = date.atTime(shift.start()).atZone(shift.zone()).toInstant();
                LocalDate endDate = shift.end().isAfter(shift.start()) ? date : date.plusDays(1);
                Instant end = endDate.atTime(shift.end()).atZone(shift.zone()).toInstant();

                List<Session> sessions = jdbc.query("""
                    SELECT clock_in_at,clock_out_at FROM time_attendance_session
                    WHERE tenant_id=? AND employee_id=? AND clock_in_at<?
                      AND (clock_out_at IS NULL OR clock_out_at>?)
                    ORDER BY clock_in_at LIMIT 501
                    """, (rs,n) -> new Session(rs.getTimestamp(1).toInstant(),
                            rs.getTimestamp(2)==null ? null : rs.getTimestamp(2).toInstant()),
                    user.tenantId(), employeeId, Timestamp.from(end), Timestamp.from(start));
                if (sessions.size()>500)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Too many attendance records");
                boolean open = sessions.stream().anyMatch(s -> s.end()==null);
                List<AttendanceVarianceCalculator.Interval> intervals = sessions.stream()
                    .filter(s -> s.end()!=null)
                    .map(s -> new AttendanceVarianceCalculator.Interval(s.start(),s.end()))
                    .toList();
                var variance = AttendanceVarianceCalculator.calculate(date, shift.start(),
                    shift.end(), shift.zone(), shift.grace(), shift.unpaidBreak(), intervals, open);
                results.add(new Preview(date, shift.id(), shift.zone().getId(),
                    variance, true, "INFORMATIONAL_NOT_PAYROLL_APPROVED"));
            }
            return results;
        });
    }
}
