package com.bramhastra.baton.tenant;

import java.io.IOException;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Selects the workspace for an authenticated request from the signed {@code tid} claim of its token.
 * Registered inside the security chain only (not as a servlet filter), right after bearer-token authentication.
 */
public class TenantFilter extends OncePerRequestFilter {

    private final TenantRegistry registry;
    private final TenantActivity activity;

    public TenantFilter(TenantRegistry registry, TenantActivity activity) {
        this.registry = registry;
        this.activity = activity;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            chain.doFilter(request, response);
            return;
        }
        Tenant tenant = null;
        String tid = jwt.getClaimAsString("tid");
        if (tid != null) {
            try {
                tenant = registry.byId(UUID.fromString(tid)).filter(Tenant::active).orElse(null);
            } catch (IllegalArgumentException ignored) {
                // malformed claim: treated as no workspace
            }
        }
        if (tenant == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"UNAUTHENTICATED\",\"message\":\"Workspace not found or disabled; sign in again\",\"details\":null}");
            return;
        }
        TenantContext.set(tenant);
        activity.touch(tenant.id());
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
