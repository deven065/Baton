package com.bramhastra.baton.security;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.identity.AppUser;
import com.bramhastra.baton.identity.OrgRole;
import com.bramhastra.baton.identity.UserGroup;

/** The authenticated caller, loaded fresh from the database on every request. */
public record CurrentUser(UUID id, String email, String displayName, OrgRole orgRole,
                          Sensitivity clearance, Set<String> groups, boolean mustChangePassword) {

    public static CurrentUser of(AppUser u) {
        Set<String> groups = u.getGroups().stream().map(UserGroup::getName)
                .collect(Collectors.toUnmodifiableSet());
        return new CurrentUser(u.getId(), u.getEmail(), u.getDisplayName(), u.getOrgRole(),
                u.getClearance(), groups, u.isMustChangePassword());
    }

    public boolean isAdmin() {
        return orgRole == OrgRole.ADMIN;
    }
}
