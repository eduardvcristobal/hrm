package com.apexmedsuite.hrm.timekeeping;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttendanceVarianceCalculatorTest {
    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    private static Instant at(int dayOffset, int hour, int minute) {
        return DAY.plusDays(dayOffset).atTime(hour, minute).atZone(MANILA).toInstant();
    }
    private static AttendanceVarianceCalculator.Interval span(int d1, int h1, int m1,
                                                               int d2, int h2, int m2) {
        return new AttendanceVarianceCalculator.Interval(at(d1,h1,m1), at(d2,h2,m2));
    }
    @Test void overnightShiftAllocatesActualMinutesWithoutPayrollApproval() {
        var result = AttendanceVarianceCalculator.calculate(DAY, LocalTime.of(22,0),
            LocalTime.of(6,0), MANILA, 5, 60,
            List.of(span(0,22,10,1,6,20)), false);
        assertEquals(480, result.scheduledMinutes());
        assertEquals(490, result.recordedMinutes());
        assertEquals(470, result.withinShiftMinutes());
        assertEquals(5L, result.lateMinutes());
        assertEquals(0L, result.undertimeMinutes());
        assertEquals(20, result.outsideShiftMinutes());
        assertEquals(60, result.unpaidBreakMinutes());
        assertFalse(result.missingPunches());
    }
    @Test void lateAndUndertimeRemainSeparateFromBreakPolicy() {
        var result = AttendanceVarianceCalculator.calculate(DAY, LocalTime.of(8,0),
            LocalTime.of(17,0), MANILA, 10, 60,
            List.of(span(0,8,20,0,16,45)), false);
        assertEquals(540, result.scheduledMinutes());
        assertEquals(505, result.recordedMinutes());
        assertEquals(10L, result.lateMinutes());
        assertEquals(15L, result.undertimeMinutes());
        assertEquals(0, result.outsideShiftMinutes());
    }
    @Test void overlappingClockRecordsAreNotDoubleCounted() {
        var result = AttendanceVarianceCalculator.calculate(DAY, LocalTime.of(8,0),
            LocalTime.of(17,0), MANILA, 0, 0,
            List.of(span(0,8,0,0,12,0),span(0,11,0,0,17,0)), false);
        assertEquals(540, result.recordedMinutes());
        assertEquals(540, result.withinShiftMinutes());
    }
    @Test void openAndMissingAttendanceNeverProduceCertifiedVariance() {
        var empty = AttendanceVarianceCalculator.calculate(DAY, LocalTime.of(8,0),
            LocalTime.of(17,0), MANILA, 0, 60, List.of(), false);
        assertTrue(empty.missingPunches());
        assertNull(empty.lateMinutes());
        assertNull(empty.undertimeMinutes());
        var open = AttendanceVarianceCalculator.calculate(DAY, LocalTime.of(8,0),
            LocalTime.of(17,0), MANILA, 0, 60,
            List.of(span(0,8,0,0,12,0)), true);
        assertNull(open.undertimeMinutes());
        assertTrue(open.missingPunches());
    }
    @Test void springForwardUsesRealElapsedTimeNotWallClockHours() {
        var zone = ZoneId.of("America/New_York");
        var day = LocalDate.of(2026,3,8);
        Instant from = day.atTime(1,0).atZone(zone).toInstant();
        Instant to = day.atTime(4,0).atZone(zone).toInstant();
        var result = AttendanceVarianceCalculator.calculate(day, LocalTime.of(1,0),
            LocalTime.of(4,0), zone, 0, 0,
            List.of(new AttendanceVarianceCalculator.Interval(from,to)),false);
        assertEquals(120, result.scheduledMinutes());
        assertEquals(120, result.recordedMinutes());
    }
    @Test void rejectsMalformedSchedulesAndIntervals() {
        assertThrows(IllegalArgumentException.class, () ->
            AttendanceVarianceCalculator.calculate(DAY,LocalTime.NOON,LocalTime.NOON,
                MANILA,0,0,List.of(),false));
        assertThrows(IllegalArgumentException.class, () ->
            span(0,17,0,0,8,0));
    }
}
