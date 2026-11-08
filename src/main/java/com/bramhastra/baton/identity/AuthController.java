package com.bramhastra.baton.identity;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.security.CurrentUserProvider;
import com.bramhastra.baton.security.JwtService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;

    public AuthController(AppUserRepository users, PasswordEncoder passwords, JwtService jwt,
                          CurrentUserProvider currentUser, AuditService audit) {
        this.users = users;
        this.passwords = passwords;
        this.jwt = jwt;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @PostMapping("/auth/login")
    @Transactional
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        AppUser user = users.findByEmailIgnoreCase(req.email().trim())
                .filter(AppUser::isActive)
                .filter(u -> passwords.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid email or password"));
        JwtService.IssuedToken token = jwt.issue(user);
        audit.record(user.getId(), "LOGIN", "app_user", user.getId(), null, Map.of());
        return new LoginResponse(token.token(), token.expiresAt(), UserView.of(CurrentUser.of(user)));
    }

    @GetMapping("/me")
    public UserView me() {
        return UserView.of(currentUser.get());
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record LoginResponse(String token, Instant expiresAt, UserView user) {
    }

    public record UserView(UUID id, String email, String displayName, OrgRole orgRole,
                           Sensitivity clearance, Set<String> groups) {
        static UserView of(CurrentUser u) {
            return new UserView(u.id(), u.email(), u.displayName(), u.orgRole(), u.clearance(), u.groups());
        }
    }
}
