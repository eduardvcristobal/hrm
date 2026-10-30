package com.apexmedsuite.hrm.platform.security;

import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Same-origin API clients must retain their session cookie and send the returned token in X-CSRF-TOKEN. */
@RestController
public class CsrfTokenController {
    @GetMapping("/api/v1/security/csrf")
    public Map<String, String> token(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }
}
