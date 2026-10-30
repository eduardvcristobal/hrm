package com.apexmedsuite.hrm.platform.security;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;

/** Central personnel authorization matrix. MFA assurance is required for every HR operation. */
public final class PersonnelAccessPolicy {
    private PersonnelAccessPolicy() {}
    private static boolean has(Collection<? extends GrantedAuthority> authorities,String role) {
        return authorities.stream().anyMatch(a -> role.equals(a.getAuthority()));
    }
    public static boolean personnel(Collection<? extends GrantedAuthority> authorities) {
        return has(authorities,"ROLE_MFA_VERIFIED") &&
            (has(authorities,"ROLE_TENANT_ADMIN") || has(authorities,"ROLE_HR_ADMIN"));
    }
    public static boolean compensation(Collection<? extends GrantedAuthority> authorities) {
        return has(authorities,"ROLE_MFA_VERIFIED") &&
            (has(authorities,"ROLE_TENANT_ADMIN") || has(authorities,"ROLE_PAYROLL_OFFICER"));
    }
}
