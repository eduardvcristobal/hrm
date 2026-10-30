package com.apexmedsuite.hrm.platform.security;

import java.util.Collection;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record HrmUserPrincipal(UUID id, UUID tenantId, String email, String passwordHash,
                               boolean active, Collection<? extends GrantedAuthority> roles) implements UserDetails {
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return roles; }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return email; }
    @Override public boolean isEnabled() { return active; }
}
