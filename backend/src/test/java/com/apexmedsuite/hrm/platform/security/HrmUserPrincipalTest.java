package com.apexmedsuite.hrm.platform.security;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.junit.jupiter.api.Assertions.*;

class HrmUserPrincipalTest {
    @Test void returnsScopedIdentityAndRoles() {
        UUID tenant = UUID.randomUUID();
        var user = new HrmUserPrincipal(UUID.randomUUID(), tenant, "user@example.test", "hash", true,
            List.of(new SimpleGrantedAuthority("ROLE_HR_ADMIN")));
        assertEquals(tenant, user.tenantId());
        assertEquals("user@example.test", user.getUsername());
        assertTrue(user.isEnabled());
        assertEquals("ROLE_HR_ADMIN", user.getAuthorities().iterator().next().getAuthority());
    }
}
