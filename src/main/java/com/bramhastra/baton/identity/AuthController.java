package com.bramhastra.baton.identity;

import java.time.Instant;
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
import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.security.CurrentUserProvider;
import com.bramhastra.baton.tenant.Tenant;
import com.bramhastra.baton.tenant.TenantContext;
import com.bramhastra.baton.tenant.TenantRegistry;

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

    public AuthController(LoginService login, TenantRegistry tenants, CurrentUserProvider currentUser,
                          @Value("${baton.tenants.default-workspace:demo}") String defaultWorkspace) {
        this.login = login;
        this.tenants = tenants;
        this.currentUser = currentUser;
        this.defaultWorkspace = defaultWorkspace;
    }

    /**
     * Signs in to one workspace. Its database is selected before anything is read, and an unknown or disabled
     * workspace gets the same answer as a wrong password, so workspace names cannot be probed.
     */
    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        String slug = req.workspace() == null || req.workspace().isBlank() ? defaultWorkspace : req.workspace();
        Tenant tenant = tenants.bySlug(slug).filter(Tenant::active)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid workspace, email or password"));
        return TenantContext.call(tenant, () -> login.login(tenant, req.email().trim(), req.password()));
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
                           Sensitivity clearance, Set<String> groups, WorkspaceView workspace) {
        static UserView of(CurrentUser u) {
            Tenant t = TenantContext.require();
            return new UserView(u.id(), u.email(), u.displayName(), u.orgRole(), u.clearance(), u.groups(),
                    new WorkspaceView(t.slug(), t.name()));
        }
    }

    public record WorkspaceView(String slug, String name) {
    }
}
