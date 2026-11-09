package com.bramhastra.baton.tenant;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.RateLimiter;
import com.bramhastra.baton.identity.AuthController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaces;
    private final TenantRegistry registry;
    private final RateLimiter limits;
    private final int signupPerIp;
    private final int lookupPerIp;

    public WorkspaceController(WorkspaceService workspaces, TenantRegistry registry, RateLimiter limits,
                               @Value("${baton.limits.signup-per-ip:5}") int signupPerIp,
                               @Value("${baton.limits.lookup-per-ip:60}") int lookupPerIp) {
        this.workspaces = workspaces;
        this.registry = registry;
        this.limits = limits;
        this.signupPerIp = signupPerIp;
        this.lookupPerIp = lookupPerIp;
    }

    /** Self-service sign-up with the company's own database. Signs the new admin in. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AuthController.LoginResponse register(@Valid @RequestBody RegisterRequest req, HttpServletRequest http) {
        // Each attempt makes Baton connect to a database someone named; keep that from being used as a scanner.
        limits.acquire("signup-ip:" + RateLimiter.clientIp(http), signupPerIp, Duration.ofHours(1));
        return workspaces.register(new WorkspaceService.Registration(req.slug(), req.name(),
                req.database().url(), req.database().username(), req.database().password(),
                req.admin().displayName(), req.admin().email(), req.admin().password()));
    }

    /** Lets the sign-in page confirm a workspace name. Reveals only its display name. */
    @GetMapping("/{slug}")
    public AuthController.WorkspaceView get(@PathVariable String slug, HttpServletRequest http) {
        limits.acquire("lookup-ip:" + RateLimiter.clientIp(http), lookupPerIp, Duration.ofMinutes(1));
        return registry.bySlug(slug).filter(Tenant::active)
                .map(t -> new AuthController.WorkspaceView(t.slug(), t.name()))
                .orElseThrow(ApiException::notFound);
    }

    public record RegisterRequest(@NotBlank @Size(max = 40) String slug,
                                  @NotBlank @Size(max = 120) String name,
                                  @Valid @NotNull Database database,
                                  @Valid @NotNull Admin admin) {
    }

    public record Database(@NotBlank @Size(max = 2000) String url, @Size(max = 200) String username,
                           @Size(max = 500) String password) {
    }

    public record Admin(@NotBlank @Size(max = 120) String displayName, @NotBlank @Email @Size(max = 200) String email,
                        @NotBlank @Size(min = 12, max = 200) String password) {
    }
}
