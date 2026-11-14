package com.bramhastra.baton.integration;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bramhastra.baton.admin.AdminService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.common.RateLimiter;
import com.bramhastra.baton.common.SafeLog;
import com.bramhastra.baton.integration.IntegrationService.Kind;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.security.CurrentUserProvider;
import com.bramhastra.baton.tenant.Tenant;
import com.bramhastra.baton.tenant.TenantContext;
import com.bramhastra.baton.tenant.TenantRegistry;

import jakarta.servlet.http.HttpServletRequest;

/**
 * One-click connections. {@code start} (an admin, signed in) returns the tool's authorization page; the tool then
 * sends the browser back to {@code callback}, which has no sign-in header, so it trusts only the signed state made by
 * {@code start}, finishes the connection in that workspace and sends the browser on to the web app.
 */
@RestController
@RequestMapping("/api")
public class OAuthController {

    private final OAuthService oauth;
    private final OAuthState states;
    private final IntegrationService integrations;
    private final TenantRegistry tenants;
    private final CurrentUserProvider currentUser;
    private final RateLimiter limits;
    private final String appUrl;

    public OAuthController(OAuthService oauth, OAuthState states, IntegrationService integrations, TenantRegistry tenants,
                           CurrentUserProvider currentUser, RateLimiter limits, @Value("${baton.app-url}") String appUrl) {
        this.oauth = oauth;
        this.states = states;
        this.integrations = integrations;
        this.tenants = tenants;
        this.currentUser = currentUser;
        this.limits = limits;
        this.appUrl = appUrl.replaceAll("/+$", "");
    }

    /** Which tools this server can connect with one click. Admins only, like the rest of Connections. */
    @GetMapping("/admin/integrations/oauth")
    public Map<String, Boolean> available() {
        AdminService.requireAdmin(currentUser.get());
        return oauth.available();
    }

    @PostMapping("/admin/integrations/{kind}/oauth/start")
    public Map<String, String> start(@PathVariable Kind kind) {
        CurrentUser me = currentUser.get();
        AdminService.requireAdmin(me);
        if (!oauth.isAvailable(kind)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "One-click connection is not set up for "
                    + IntegrationService.label(kind) + " on this server. Paste a key instead.");
        }
        String state = states.sign(TenantContext.require().id(), me.id(), kind);
        return Map.of("url", oauth.authorizeUrl(kind, state));
    }

    @GetMapping("/oauth/{kind}/callback")
    public ResponseEntity<Void> callback(@PathVariable("kind") String kindName, @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error, HttpServletRequest request) {
        Kind kind;
        try {
            kind = Kind.valueOf(kindName.toUpperCase(Locale.ROOT)); // the address uses lower case: /api/oauth/slack/callback
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        limits.acquire("oauth-callback:" + request.getRemoteAddr(), 30, Duration.ofMinutes(10));
        OAuthState.Payload p = states.verify(state, kind);
        if (p == null) {
            return back(kind, "That link expired. Start the connection again from Organisation → Connections.");
        }
        if (error != null || code == null || code.isBlank()) {
            return back(kind, IntegrationService.label(kind) + " did not approve the connection.");
        }
        Tenant tenant = tenants.byId(p.tenantId()).filter(Tenant::active).orElse(null);
        if (tenant == null) {
            return back(kind, "That workspace is no longer available.");
        }
        try {
            OAuthService.Connected c = oauth.complete(kind, code);
            TenantContext.run(tenant, () -> integrations.saveOAuth(p.userId(), kind, c.secret(), c.hint()));
            return back(kind, null);
        } catch (IllegalArgumentException | ApiException e) {
            return back(kind, e.getMessage());
        } catch (RuntimeException e) {
            return back(kind, "Could not finish connecting " + IntegrationService.label(kind) + " ("
                    + SafeLog.describe(e).split(" ")[0] + ")");
        }
    }

    /** Back to Organisation → Connections, with the outcome in the address. */
    private ResponseEntity<Void> back(Kind kind, String problem) {
        String target = appUrl + "/app/admin/connections?" + (problem == null
                ? "connected=" + kind.name().toLowerCase(Locale.ROOT)
                : "connectError=" + URLEncoder.encode(problem, StandardCharsets.UTF_8));
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, target).build();
    }
}
