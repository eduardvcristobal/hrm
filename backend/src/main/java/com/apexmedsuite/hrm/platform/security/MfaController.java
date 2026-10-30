package com.apexmedsuite.hrm.platform.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/security/mfa")
public class MfaController {
    private final MfaService mfa;
    public MfaController(MfaService mfa){this.mfa=mfa;}
    public record OneTimeCode(@NotBlank @Pattern(regexp="[0-9]{6}") String code){}
    @PostMapping("/enroll")
    @ResponseStatus(HttpStatus.CREATED)
    public MfaService.Enrollment enroll(@AuthenticationPrincipal HrmUserPrincipal user){
        return mfa.enroll(user);
    }
    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@AuthenticationPrincipal HrmUserPrincipal user,
                        @Valid @RequestBody OneTimeCode request){
        mfa.confirm(user,request.code());
    }
    @GetMapping
    public java.util.Map<String,Boolean> status(@AuthenticationPrincipal HrmUserPrincipal user) {
        return java.util.Map.of("enabled",mfa.isEnabled(user.id()));
    }
}
