package com.bramhastra.baton.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.common.RateLimiter;
import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.security.CurrentUserProvider;
import com.bramhastra.baton.tenant.Tenant;
import com.bramhastra.baton.tenant.TenantContext;
import com.bramhastra.baton.tenant.TenantRegistry;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final LoginService login;
    private final TenantRegistry tenants;
    private final CurrentUserProvider currentUser;
    private final String defaultWorkspace;
    private final RateLimiter limits;
    private final int perIp;
    private final int failuresPerAccount;

    private static final Duration IP_WINDOW = Duration.ofMinutes(10);
    private static final Duration ACCOUNT_WINDOW = Duration.ofMinutes(15);

    public AuthController(LoginService login, TenantRegistry tenants, CurrentUserProvider currentUser,
                          @Value("${baton.tenants.default-workspace:demo}") String defaultWorkspace, RateLimiter limits,
                          @Value("${baton.limits.login-per-ip:30}") int perIp,
                          @Value("${baton.limits.login-failures-per-account:8}") int failuresPerAccount) {
        this.login = login;
        this.tenants = tenants;
        this.currentUser = currentUser;
        this.defaultWorkspace = defaultWorkspace;
        this.limits = limits;
        this.perIp = perIp;
        this.failuresPerAccount = failuresPerAccount;
    }

    /**
     * Signs in to one workspace. Its database is selected before anything is read, and an unknown or disabled
     * workspace gets the same answer as a wrong password, so workspace names cannot be probed.
     */
    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        String slug = req.workspace() == null || req.workspace().isBlank() ? defaultWorkspace : req.workspace().strip();
        String email = req.email().strip();
        // Two limits: attempts from one address (any account), and wrong passwords for one account (any address).
        limits.acquire("login-ip:" + RateLimiter.clientIp(http), perIp, IP_WINDOW);
        String account = "login-account:" + slug.toLowerCase(Locale.ROOT) + ":" + email.toLowerCase(Locale.ROOT);
        limits.check(account, failuresPerAccount, ACCOUNT_WINDOW);
        try {
            Tenant tenant = tenants.bySlug(slug).filter(Tenant::active)
                    .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid workspace, email or password"));
            LoginResponse ok = TenantContext.call(tenant, () -> login.login(tenant, email, req.password()));
            limits.reset(account);
            return ok;
        } catch (ApiException e) {
            if (e.code() == ErrorCode.UNAUTHENTICATED) {
                limits.hit(account, ACCOUNT_WINDOW);
            }
            throw e;
        }
    }

    @GetMapping("/me")
    public UserView me() {
        return UserView.of(currentUser.get());
    }

    public record LoginRequest(@Size(max = 40) String workspace, @NotBlank @Email String email, @NotBlank String password) {
    }

    public record LoginResponse(String token, Instant expiresAt, UserView user) {
    }

    public record UserView(UUID id, String email, String displayName, OrgRole orgRole,
                           Sensitivity clearance, Set<String> groups, boolean mustChangePassword,
                           WorkspaceView workspace) {
        static UserView of(CurrentUser u) {
            Tenant t = TenantContext.require();
            return new UserView(u.id(), u.email(), u.displayName(), u.orgRole(), u.clearance(), u.groups(),
                    u.mustChangePassword(), new WorkspaceView(t.slug(), t.name()));
        }
    }

    public record WorkspaceView(String slug, String name) {
    }
}
