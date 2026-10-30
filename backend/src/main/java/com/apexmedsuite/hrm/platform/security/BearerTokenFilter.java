package com.apexmedsuite.hrm.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class BearerTokenFilter extends OncePerRequestFilter {
    private final ApiTokenService tokens;
    private final PlatformAdminTokenService platformAdmins;
    public BearerTokenFilter(ApiTokenService tokens, PlatformAdminTokenService platformAdmins) {
        this.tokens=tokens;
        this.platformAdmins=platformAdmins;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        String header=request.getHeader("Authorization");
        if(header!=null && header.startsWith("Bearer ")) {
            String raw=header.substring(7);
            if(!raw.matches("[0-9a-f]{64}")) {response.setStatus(401);return;}
            PlatformAdminPrincipal platformAdmin=platformAdmins.authenticate(raw);
            if(platformAdmin!=null) {
                SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(platformAdmin,null,platformAdmin.getAuthorities()));
            } else {
                HrmUserPrincipal user=tokens.authenticate(raw);
                if(user==null){response.setStatus(401);return;}
                SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(user,null,user.getAuthorities()));
            }
        }
        chain.doFilter(request,response);
    }
}
