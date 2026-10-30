package com.apexmedsuite.hrm.platform.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/platform-admin/mfa")
public class PlatformAdminMfaController {
    private final PlatformAdminMfaService mfa;
    public PlatformAdminMfaController(PlatformAdminMfaService mfa){this.mfa=mfa;}
    public record OneTimeCode(@NotBlank @Pattern(regexp="[0-9]{6}") String code){}

    @GetMapping
    public PlatformAdminMfaService.Status status(@AuthenticationPrincipal PlatformAdminPrincipal admin){return mfa.status(admin);}

    @PostMapping("/enroll")
    @ResponseStatus(HttpStatus.CREATED)
    public PlatformAdminMfaService.Enrollment enroll(@AuthenticationPrincipal PlatformAdminPrincipal admin){return mfa.enroll(admin);}

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@AuthenticationPrincipal PlatformAdminPrincipal admin,@Valid @RequestBody OneTimeCode input,
            @RequestHeader("Authorization") String authorization){mfa.confirm(admin,input.code(),token(authorization));}

    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verify(@AuthenticationPrincipal PlatformAdminPrincipal admin,@Valid @RequestBody OneTimeCode input,
            @RequestHeader("Authorization") String authorization){mfa.verify(admin,input.code(),token(authorization));}

    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@AuthenticationPrincipal PlatformAdminPrincipal admin,@Valid @RequestBody OneTimeCode input){
        mfa.disable(admin,input.code());
    }

    private static String token(String authorization){
        if(authorization==null||!authorization.startsWith("Bearer "))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sign in again to continue.");
        return authorization.substring(7);
    }
}
