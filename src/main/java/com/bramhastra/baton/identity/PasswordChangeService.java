package com.bramhastra.baton.identity;

import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.security.CurrentUser;

/** Lets a signed-in person replace their password; clears the "must change" flag set by an admin. */
@Service
public class PasswordChangeService {

    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final AuditService audit;

    public PasswordChangeService(AppUserRepository users, PasswordEncoder passwords, AuditService audit) {
        this.users = users;
        this.passwords = passwords;
        this.audit = audit;
    }

    @Transactional
    public void change(CurrentUser me, String current, String next) {
        AppUser u = users.findById(me.id()).orElseThrow(ApiException::notFound);
        if (!passwords.matches(current, u.getPasswordHash())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    Map.of("currentPassword", "That is not your current password"));
        }
        if (passwords.matches(next, u.getPasswordHash())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    Map.of("newPassword", "Choose a password different from the current one"));
        }
        u.setPasswordHash(passwords.encode(next));
        u.setMustChangePassword(false);
        users.saveAndFlush(u);
        audit.record(u.getId(), "PASSWORD_CHANGED", "app_user", u.getId(), null, Map.of());
    }
}
