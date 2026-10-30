package com.apexmedsuite.hrm.platform.security;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.junit.jupiter.api.Assertions.*;

class PersonnelAccessPolicyTest {
    private static List<SimpleGrantedAuthority> roles(String... names) {
        return java.util.Arrays.stream(names).map(SimpleGrantedAuthority::new).toList();
    }
    @Test void hrAdminCanManagePersonnelButNotSalaries() {
        var roles=roles("ROLE_HR_ADMIN","ROLE_MFA_VERIFIED");
        assertTrue(PersonnelAccessPolicy.personnel(roles));
        assertFalse(PersonnelAccessPolicy.compensation(roles));
    }
    @Test void payrollOfficerCanReadCompensationButNotManageProfiles() {
        var roles=roles("ROLE_PAYROLL_OFFICER","ROLE_MFA_VERIFIED");
        assertFalse(PersonnelAccessPolicy.personnel(roles));
        assertTrue(PersonnelAccessPolicy.compensation(roles));
    }
    @Test void tenantAdminCanAccessBothWithMfaOnly() {
        assertTrue(PersonnelAccessPolicy.personnel(roles("ROLE_TENANT_ADMIN","ROLE_MFA_VERIFIED")));
        assertTrue(PersonnelAccessPolicy.compensation(roles("ROLE_TENANT_ADMIN","ROLE_MFA_VERIFIED")));
        assertFalse(PersonnelAccessPolicy.personnel(roles("ROLE_TENANT_ADMIN")));
        assertFalse(PersonnelAccessPolicy.compensation(roles("ROLE_TENANT_ADMIN")));
    }
    @Test void managerAndEmployeeAndAuditorCannotAccessAdminPersonnel() {
        for(String role:List.of("ROLE_MANAGER","ROLE_EMPLOYEE","ROLE_AUDITOR")) {
            var roles=roles(role,"ROLE_MFA_VERIFIED");
            assertFalse(PersonnelAccessPolicy.personnel(roles));
            assertFalse(PersonnelAccessPolicy.compensation(roles));
        }
    }
}
