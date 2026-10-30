package com.apexmedsuite.hrm.platform.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/security/tokens")
public class TokenController {
    private final ApiTokenService tokens;
    private final MfaService mfa;
    public TokenController(ApiTokenService tokens,MfaService mfa){this.tokens=tokens;this.mfa=mfa;}
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiTokenService.Token issue(@AuthenticationPrincipal HrmUserPrincipal user,
            @RequestHeader(value="X-TOTP-Code",required=false) String code){
        if(mfa.isEnabled(user.id())) mfa.requireVerified(user,code);
        return tokens.issue(user);
    }
    @DeleteMapping("/current")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@RequestHeader("Authorization") String header,@AuthenticationPrincipal HrmUserPrincipal user){
        if(header.startsWith("Bearer ")) tokens.revoke(header.substring(7),user);
        else throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"Use Bearer token to revoke");
    }
}
