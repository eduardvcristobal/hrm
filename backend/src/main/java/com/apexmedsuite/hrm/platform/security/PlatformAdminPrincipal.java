package com.apexmedsuite.hrm.platform.security;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record PlatformAdminPrincipal(UUID id, String email, boolean mfaVerified, boolean mfaEnabled) implements UserDetails {
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        if (mfaVerified) return List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"),new SimpleGrantedAuthority("ROLE_MFA_VERIFIED"));
        return List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
    }
    @Override public String getPassword() { return ""; }
    @Override public String getUsername() { return email; }
    @Override public boolean isEnabled() { return true; }
}
