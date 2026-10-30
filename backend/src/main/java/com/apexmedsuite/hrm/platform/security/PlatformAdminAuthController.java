package com.apexmedsuite.hrm.platform.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/security/platform-admin")
public class PlatformAdminAuthController {
    private final GoogleIdTokenVerifier google;
    private final PlatformAdminTokenService tokens;

    public PlatformAdminAuthController(GoogleIdTokenVerifier google,PlatformAdminTokenService tokens) {
        this.google=google;
        this.tokens=tokens;
    }

    public record GoogleLogin(@NotBlank @Size(max=8192) String idToken) {
        @Override public String toString() { return "GoogleLogin[idToken=[REDACTED]]"; }
    }

    @PostMapping("/google")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiTokenService.Token signIn(@Valid @RequestBody GoogleLogin request) {
        return tokens.issue(google.verifiedEmail(request.idToken()));
    }

    @DeleteMapping("/tokens/current")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void signOut(@RequestHeader("Authorization") String authorization,
            @AuthenticationPrincipal PlatformAdminPrincipal admin) {
        if(authorization==null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a Bearer token to sign out");
        }
        tokens.revoke(authorization.substring(7),admin.id());
    }
}
