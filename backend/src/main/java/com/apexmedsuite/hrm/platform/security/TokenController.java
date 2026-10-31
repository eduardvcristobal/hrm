package com.apexmedsuite.hrm.platform.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/security/tokens")
public class TokenController {
    private final ApiTokenService tokens;
    private final MfaService mfa;
    private final WorkspaceAuditService audit;
    public TokenController(ApiTokenService tokens,MfaService mfa,WorkspaceAuditService audit){this.tokens=tokens;this.mfa=mfa;this.audit=audit;}
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiTokenService.Token issue(@AuthenticationPrincipal HrmUserPrincipal user,
            @RequestHeader(value="X-TOTP-Code",required=false) String code){
        boolean mfaEnabled=mfa.isEnabled(user.id());
        // Privileged identities must not mint an access token before MFA enrollment.
        boolean privileged=user.getAuthorities().stream().anyMatch(authority ->
            java.util.Set.of("ROLE_TENANT_ADMIN","ROLE_HR_ADMIN","ROLE_PAYROLL_OFFICER")
                .contains(authority.getAuthority()));
        if(privileged && !mfaEnabled)
            throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.FORBIDDEN,"Privileged account authenticator enrollment required");
        if(mfaEnabled) {
            if(code==null || code.isBlank())
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"Authenticator code required");
            mfa.requireVerified(user,code);
        }
        ApiTokenService.Token token=tokens.issue(user,mfaEnabled);
        audit.record(user.tenantId(),user.id(),"USER_SIGNED_IN","auth_identity",user.id());
        return token;
    }
    @DeleteMapping("/current")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@RequestHeader("Authorization") String header,@AuthenticationPrincipal HrmUserPrincipal user){
        if(header.startsWith("Bearer ")) tokens.revoke(header.substring(7),user);
        else throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"Use Bearer token to revoke");
    }
}
