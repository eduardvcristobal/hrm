package com.apexmedsuite.hrm.personnel;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Pure domain guard shared by request and approval, re-evaluated at decision time. */
public final class RehireEligibility {
    private RehireEligibility() {}
    public static boolean eligible(String status, LocalDate separation, LocalDate effectiveDate,
                                   boolean enabled, int minimumWaitDays) {
        return enabled && "TERMINATED".equals(status) && separation != null && effectiveDate != null
            && minimumWaitDays >= 0 && minimumWaitDays <= 3650
            && ChronoUnit.DAYS.between(separation,effectiveDate) >= Math.max(1,minimumWaitDays);
    }
}
