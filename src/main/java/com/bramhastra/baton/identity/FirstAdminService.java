package com.bramhastra.baton.identity;

import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.security.JwtService;
import com.bramhastra.baton.tenant.Tenant;

/** Creates a new workspace's first account (organisation admin) in that company's own database. */
@Service
public class FirstAdminService {

    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final AuditService audit;

    public FirstAdminService(AppUserRepository users, PasswordEncoder passwords, JwtService jwt, AuditService audit) {
        this.users = users;
        this.passwords = passwords;
        this.jwt = jwt;
        this.audit = audit;
    }

    @Transactional
    public AuthController.LoginResponse create(Tenant tenant, String name, String email, String password) {
        AppUser u = new AppUser();
        u.setEmail(email.toLowerCase(java.util.Locale.ROOT));
        u.setDisplayName(name);
        u.setPasswordHash(passwords.encode(password));
        u.setOrgRole(OrgRole.ADMIN);
        u.setClearance(Sensitivity.RESTRICTED);
        u = users.saveAndFlush(u); // flush: the audit row below references this user
        audit.record(u.getId(), "WORKSPACE_CREATED", "app_user", u.getId(), null, Map.of("workspace", tenant.slug()));
        JwtService.IssuedToken token = jwt.issue(u, tenant);
        return new AuthController.LoginResponse(token.token(), token.expiresAt(),
                AuthController.UserView.of(CurrentUser.of(u)));
    }
}
