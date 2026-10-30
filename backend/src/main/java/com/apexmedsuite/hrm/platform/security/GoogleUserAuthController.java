package com.apexmedsuite.hrm.platform.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/security")
public class GoogleUserAuthController {
    private final GoogleIdTokenVerifier google;
    private final DatabaseUserDetailsService users;
    private final ApiTokenService tokens;
    private final MfaService mfa;
    private final WorkspaceAuditService audit;

    public GoogleUserAuthController(GoogleIdTokenVerifier google,DatabaseUserDetailsService users,
            ApiTokenService tokens,MfaService mfa,WorkspaceAuditService audit) {
        this.google=google;
        this.users=users;
        this.tokens=tokens;
        this.mfa=mfa;
        this.audit=audit;
    }

    public record GoogleLogin(@NotBlank @Size(max=8192) String idToken) {
        @Override public String toString() { return "GoogleLogin[idToken=[REDACTED]]"; }
    }

    @PostMapping("/google")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiTokenService.Token signIn(@Valid @RequestBody GoogleLogin request,
            @RequestHeader(value="X-TOTP-Code",required=false) String code) {
        String email=google.verifiedEmail(request.idToken());
        HrmUserPrincipal user;
        try {
            user=(HrmUserPrincipal)users.loadUserByUsername(email);
        } catch(UsernameNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Google sign-in could not be verified");
        }
        if(!user.active()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Google sign-in could not be verified");
        }
        boolean mfaEnabled=mfa.isEnabled(user.id());
        // MFA is a login challenge only after the user enrolls an authenticator.
        // Otherwise authorization is determined by the account's assigned roles.
        if(mfaEnabled) {
            if(code==null || code.isBlank())
                throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"Authenticator code required");
            mfa.requireVerified(user,code);
        }
        ApiTokenService.Token token=tokens.issue(user,mfaEnabled);
        audit.record(user.tenantId(),user.id(),"USER_SIGNED_IN","auth_identity",user.id());
        return token;
    }
}
