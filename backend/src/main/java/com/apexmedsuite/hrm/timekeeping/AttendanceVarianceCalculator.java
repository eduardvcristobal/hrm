package com.apexmedsuite.hrm.timekeeping;

import java.time.*;
import java.util.Comparator;
import java.util.List;

/**
 * M2.3 informational shift variance. Deliberately does not calculate payable
 * hours, statutory premiums, holiday attribution or approved overtime.
 */
public final class AttendanceVarianceCalculator {
    private AttendanceVarianceCalculator() {}

    public record Interval(Instant start, Instant end) {
        public Interval {
            if (start == null || end == null || end.isBefore(start))
                throw new IllegalArgumentException("Invalid attendance interval");
        }
    }

    public record Result(long scheduledMinutes, long recordedMinutes,
                         long withinShiftMinutes, Long lateMinutes,
                         Long undertimeMinutes, long outsideShiftMinutes,
                         int unpaidBreakMinutes, boolean missingPunches) {}

    /**
     * The schedule date is local to the shift's configured IANA timezone.
     * An end time earlier than the start marks an overnight shift.
     * The result is a preview, not a payroll or DTR certification.
     */
    public static Result calculate(LocalDate day, LocalTime start, LocalTime end,
                                   ZoneId zone, int graceMinutes, int unpaidBreakMinutes,
                                   List<Interval> sessions, boolean openSession) {
        if (day == null || start == null || end == null || zone == null ||
            sessions == null || start.equals(end) || graceMinutes < 0 ||
            unpaidBreakMinutes < 0)
            throw new IllegalArgumentException("Invalid schedule policy");
        ZonedDateTime localStart = ZonedDateTime.of(day, start, zone);
        LocalDate finishDay = end.isAfter(start) ? day : day.plusDays(1);
        ZonedDateTime localEnd = ZonedDateTime.of(finishDay, end, zone);
        Instant shiftStart = localStart.toInstant();
        Instant shiftEnd = localEnd.toInstant();
        long planned = Duration.between(shiftStart, shiftEnd).toMinutes();
        if (planned <= 0 || planned > 24 * 60 || unpaidBreakMinutes >= planned)
            throw new IllegalArgumentException("Invalid planned shift duration");

        List<Interval> ordered = sessions.stream()
            .sorted(Comparator.comparing(Interval::start)).toList();
        // Merge overlapping intervals: duplicate device records must never
        // double-count employee attendance.
        Instant first = null, last = null, unionEnd = null;
        long recordedSeconds = 0, insideSeconds = 0;
        for (Interval interval : ordered) {
            if (first == null || interval.start().isBefore(first)) first = interval.start();
            if (last == null || interval.end().isAfter(last)) last = interval.end();
            Instant left = unionEnd == null || interval.start().isAfter(unionEnd)
                ? interval.start() : unionEnd;
            if (interval.end().isAfter(left)) {
                recordedSeconds += Duration.between(left, interval.end()).getSeconds();
                Instant withinStart = left.isAfter(shiftStart) ? left : shiftStart;
                Instant withinEnd = interval.end().isBefore(shiftEnd) ? interval.end() : shiftEnd;
                if (withinEnd.isAfter(withinStart))
                    insideSeconds += Duration.between(withinStart, withinEnd).getSeconds();
            }
            if (unionEnd == null || interval.end().isAfter(unionEnd)) unionEnd = interval.end();
        }
        long recorded = recordedSeconds / 60;
        long inside = insideSeconds / 60;
        long outside = (recordedSeconds - insideSeconds) / 60;
        // An open clock or absent clock pair makes late/undertime provisional.
        boolean incomplete = openSession || ordered.isEmpty();
        Long late = incomplete ? null : Math.max(0,
            Duration.between(shiftStart, first).toMinutes() - graceMinutes);
        Long undertime = incomplete ? null : Math.max(0,
            Duration.between(last, shiftEnd).toMinutes());
        return new Result(planned, recorded, inside, late, undertime,
            outside, unpaidBreakMinutes, incomplete);
    }
}
