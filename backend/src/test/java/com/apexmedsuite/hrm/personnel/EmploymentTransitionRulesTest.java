package com.apexmedsuite.hrm.personnel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmploymentTransitionRulesTest {
    @Test void validActiveAndLeaveTransitions() {
        assertTrue(EmploymentTransitionRules.allowed("ACTIVE","ON_LEAVE","REGULAR","REGULAR"));
        assertTrue(EmploymentTransitionRules.allowed("ON_LEAVE","ACTIVE","REGULAR","REGULAR"));
        assertTrue(EmploymentTransitionRules.allowed("ACTIVE","ACTIVE","PROBATIONARY","REGULAR"));
        assertTrue(EmploymentTransitionRules.allowed("SUSPENDED","TERMINATED","REGULAR","REGULAR"));
    }
    @Test void separatedWorkersCannotReactivateViaOrdinaryEmploymentEndpoint() {
        for(String previous:new String[]{"TERMINATED","RETIRED"}) {
            for(String next:new String[]{"ACTIVE","TERMINATED","RETIRED","ON_LEAVE","SUSPENDED"})
                assertFalse(EmploymentTransitionRules.allowed(previous,next,"REGULAR","REGULAR"));
        }
    }
    @Test void invalidAndNoOpTransitionsFailClosed() {
        assertFalse(EmploymentTransitionRules.allowed("ACTIVE","ACTIVE","REGULAR","REGULAR"));
        assertFalse(EmploymentTransitionRules.allowed("ON_LEAVE","ON_LEAVE","REGULAR","PART_TIME"));
        assertFalse(EmploymentTransitionRules.allowed("SUSPENDED","ON_LEAVE","REGULAR","REGULAR"));
        assertFalse(EmploymentTransitionRules.allowed(null,"ACTIVE","REGULAR","REGULAR"));
    }
}
