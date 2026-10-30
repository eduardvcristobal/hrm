package com.apexmedsuite.hrm.platform.security;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.jdbc.core.JdbcTemplate;

@RestController
@RequestMapping("/api/v1")
public class CurrentUserController {
    private final JdbcTemplate jdbc;
    public CurrentUserController(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record CurrentUser(UUID id, UUID tenantId, String email, List<String> roles, boolean platformAdmin,
                              String workspaceName) {}
    @GetMapping("/me")
    public CurrentUser me(Authentication authentication) {
        Object principal=authentication.getPrincipal();
        if(principal instanceof HrmUserPrincipal user) {
            return new CurrentUser(user.id(),user.tenantId(),user.email(),
                user.roles().stream().map(a -> a.getAuthority()).sorted().toList(),false,
                jdbc.query("SELECT display_name FROM tenant WHERE id=?", rs -> rs.next() ? rs.getString(1) : null, user.tenantId()));
        }
        if(principal instanceof PlatformAdminPrincipal admin) {
            return new CurrentUser(admin.id(),null,admin.email(),List.of("ROLE_PLATFORM_ADMIN"),true,null);
        }
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.UNAUTHORIZED,"Invalid identity");
    }
}
