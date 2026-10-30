package com.apexmedsuite.hrm.platform.security;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final JdbcTemplate jdbc;
    public DatabaseUserDetailsService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (username == null || username.isBlank()) throw new UsernameNotFoundException("Invalid credentials");
        var accounts = jdbc.query("""
            SELECT i.id, i.tenant_id, i.email, i.password_hash,
                   (i.enabled AND t.status='ACTIVE') AS enabled
            FROM auth_identity i JOIN tenant t ON t.id=i.tenant_id
            WHERE lower(i.email) = ? OR lower(i.username) = ?
            """, (rs, row) -> new HrmUserPrincipal(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("email"), rs.getString("password_hash"), rs.getBoolean("enabled"),
                List.of()), username.trim().toLowerCase(java.util.Locale.ROOT),
                username.trim().toLowerCase(java.util.Locale.ROOT));
        if (accounts.isEmpty()) throw new UsernameNotFoundException("Invalid credentials");
        var account = accounts.get(0);
        var roles = jdbc.query("SELECT role FROM auth_identity_role WHERE identity_id = ?",
            (rs, row) -> new SimpleGrantedAuthority("ROLE_" + rs.getString("role")), account.id());
        return new HrmUserPrincipal(account.id(), account.tenantId(), account.email(),
            account.passwordHash(), account.active(), roles);
    }
}
