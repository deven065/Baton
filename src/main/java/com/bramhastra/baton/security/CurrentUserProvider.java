package com.bramhastra.baton.security;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

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
        CurrentUser me = users.findById(id)
                .filter(AppUser::isActive)
                .map(CurrentUser::of)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Account not active"));
        // A temporary password only lets you read your profile and choose your own password. Enforced here, not
        // only by the web app, so the API cannot be used directly to skip the change.
        if (me.mustChangePassword() && !passwordChangeAllowed()) {
            throw new ApiException(ErrorCode.PASSWORD_CHANGE_REQUIRED, "Choose your own password before continuing");
        }
        return me;
    }

    private static boolean passwordChangeAllowed() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return true; // not a web request (none use this today)
        }
        HttpServletRequest req = attrs.getRequest();
        String path = req.getRequestURI();
        return ("GET".equals(req.getMethod()) && "/api/me".equals(path))
                || ("POST".equals(req.getMethod()) && "/api/me/password".equals(path));
    }
}
