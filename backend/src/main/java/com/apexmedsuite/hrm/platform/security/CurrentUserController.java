package com.apexmedsuite.hrm.platform.security;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CurrentUserController {
    public record CurrentUser(UUID id, UUID tenantId, String email, List<String> roles) {}
    @GetMapping("/me")
    public CurrentUser me(@AuthenticationPrincipal HrmUserPrincipal principal) {
        return new CurrentUser(principal.id(), principal.tenantId(), principal.email(),
            principal.roles().stream().map(a -> a.getAuthority()).sorted().toList());
    }
}
