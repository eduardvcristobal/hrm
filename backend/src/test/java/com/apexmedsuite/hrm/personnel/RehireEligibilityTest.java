package com.apexmedsuite.hrm.personnel;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RehireEligibilityTest {
    private static final LocalDate EXIT=LocalDate.of(2026,8,1);
    @Test void waitingPeriodBoundaries() {
        assertFalse(RehireEligibility.eligible("TERMINATED",EXIT,EXIT,true,0));
        assertTrue(RehireEligibility.eligible("TERMINATED",EXIT,EXIT.plusDays(1),true,0));
        assertFalse(RehireEligibility.eligible("TERMINATED",EXIT,EXIT.plusDays(29),true,30));
        assertTrue(RehireEligibility.eligible("TERMINATED",EXIT,EXIT.plusDays(30),true,30));
    }
    @Test void denyDisabledAndNonSeparated() {
        assertFalse(RehireEligibility.eligible("TERMINATED",EXIT,EXIT.plusDays(90),false,0));
        assertFalse(RehireEligibility.eligible("ACTIVE",EXIT,EXIT.plusDays(90),true,0));
        assertFalse(RehireEligibility.eligible("RETIRED",EXIT,EXIT.plusDays(90),true,0));
        assertFalse(RehireEligibility.eligible("TERMINATED",null,EXIT.plusDays(90),true,0));
        assertFalse(RehireEligibility.eligible("TERMINATED",EXIT,EXIT.plusDays(90),true,-1));
    }
}
