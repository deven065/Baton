package com.bramhastra.baton.security;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.UrlPathHelper;

import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.tenant.TenantContext;
import com.bramhastra.baton.tenant.TenantRegistry;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The demo workspace is shared and its passwords are public, so anyone can sign in as any demo person. Changes
 * that would break it for the next visitor (passwords, deactivating or demoting people, removing team members,
 * the workspace's AI, connections and plan) are turned off there. The handover workflow itself stays open, and
 * adding people, groups and projects still works. Every other workspace is unaffected.
 */
@Configuration
public class DemoGuard implements WebMvcConfigurer, HandlerInterceptor {

    private record Rule(String method, Pattern path) {
    }

    private static final List<Rule> LOCKED = List.of(
            new Rule("POST", Pattern.compile("/api/me/password")),
            new Rule("PATCH", Pattern.compile("/api/admin/users/[^/]+")),
            new Rule("POST", Pattern.compile("/api/admin/users/[^/]+/reset-password")),
            new Rule("DELETE", Pattern.compile("/api/admin/groups/[^/]+")),
            new Rule("PATCH", Pattern.compile("/api/projects/[^/]+/members/[^/]+")),
            new Rule("DELETE", Pattern.compile("/api/projects/[^/]+/members/[^/]+")),
            new Rule("POST", Pattern.compile("/api/shares/[^/]+/revoke")),
            // Who can see a source: one visitor making the restricted contract public would spoil "View as" for all.
            new Rule("PATCH", Pattern.compile("/api/sources/[^/]+")),
            new Rule("*", Pattern.compile("/api/admin/(ai|integrations|workspace|billing)(/.*)?")));

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final boolean locked;

    public DemoGuard(@Value("${baton.demo.locked:true}") boolean locked) {
        this.locked = locked;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if (!locked || method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS")) {
            return true;
        }
        boolean demo = TenantContext.current().map(t -> TenantRegistry.DEMO_SLUG.equals(t.slug())).orElse(false);
        if (!demo) {
            return true;
        }
        // The same normalised path Spring routes on, so ";", "%2F" or "//" tricks cannot slip past.
        String path = PATHS.getLookupPathForRequest(request);
        for (Rule r : LOCKED) {
            if ((r.method().equals("*") || r.method().equals(method)) && r.path().matcher(path).matches()) {
                throw new ApiException(ErrorCode.DEMO_LOCKED,
                        "The demo workspace is shared, so this change is turned off here. Create your own workspace to try it.");
            }
        }
        return true;
    }
}
