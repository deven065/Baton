package com.bramhastra.baton.security;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.identity.AppUser;
import com.bramhastra.baton.identity.AppUserRepository;

/**
 * Resolves the caller from the JWT subject. Clearance and groups are read from the
 * database, not from the token, so a permission change applies immediately.
 */
@Component
public class CurrentUserProvider {

    private final AppUserRepository users;

    public CurrentUserProvider(AppUserRepository users) {
        this.users = users;
    }

    public CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Not signed in");
        }
        UUID id;
        try {
            id = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid token");
        }
        return users.findById(id)
                .filter(AppUser::isActive)
                .map(CurrentUser::of)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Account not active"));
    }
}
