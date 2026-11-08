package com.bramhastra.baton.identity;

import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.security.JwtService;
import com.bramhastra.baton.tenant.Tenant;

/** Password check and token issue. Runs with the workspace already selected, so it reads that company's users. */
@Service
public class LoginService {

    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final AuditService audit;

    public LoginService(AppUserRepository users, PasswordEncoder passwords, JwtService jwt, AuditService audit) {
        this.users = users;
        this.passwords = passwords;
        this.jwt = jwt;
        this.audit = audit;
    }

    @Transactional
    public AuthController.LoginResponse login(Tenant tenant, String email, String password) {
        AppUser user = users.findByEmailIgnoreCase(email)
                .filter(AppUser::isActive)
                .filter(u -> passwords.matches(password, u.getPasswordHash()))
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid workspace, email or password"));
        JwtService.IssuedToken token = jwt.issue(user, tenant);
        audit.record(user.getId(), "LOGIN", "app_user", user.getId(), null, Map.of());
        return new AuthController.LoginResponse(token.token(), token.expiresAt(),
                AuthController.UserView.of(CurrentUser.of(user)));
    }
}
