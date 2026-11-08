package com.bramhastra.baton.security;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.identity.ProjectMember;
import com.bramhastra.baton.identity.ProjectMemberRepository;
import com.bramhastra.baton.identity.ProjectRole;

/**
 * The single permission rule, applied on every read path.
 *
 * <p>An item is visible when the viewer is a member of its project, its sensitivity is at most the
 * viewer's clearance, and it either has no ACL groups or shares at least one group with the viewer.
 * The same rule exists twice: {@link #canSee} for Java and {@link #sqlPredicate} for queries.
 * Filtering always happens on the server; the UI hides nothing for security.
 */
@Component
public class AccessPolicy {

    private final ProjectMemberRepository members;

    public AccessPolicy(ProjectMemberRepository members) {
        this.members = members;
    }

    /** Java form of the rule for an item already known to be in a project the viewer belongs to. */
    public boolean canSee(CurrentUser viewer, Sensitivity sensitivity, Collection<String> aclGroups) {
        if (!sensitivity.atMost(viewer.clearance())) {
            return false;
        }
        if (aclGroups == null || aclGroups.isEmpty()) {
            return true;
        }
        return aclGroups.stream().anyMatch(viewer.groups()::contains);
    }

    /**
     * SQL form of the rule for a table alias that has {@code sensitivity} and {@code acl_groups}
     * columns. Bind the parameters from {@link #sqlParams}.
     */
    public String sqlPredicate(String alias) {
        return "(" + alias + ".sensitivity <= :clearance AND (cardinality(" + alias + ".acl_groups) = 0 OR "
                + alias + ".acl_groups && CAST(:groups AS text[])))";
    }

    public Map<String, Object> sqlParams(CurrentUser viewer) {
        return Map.of("clearance", viewer.clearance().level(), "groups", toPgArray(viewer.groups()));
    }

    /**
     * Returns the viewer's role in the project. A non-member gets NOT_FOUND, never FORBIDDEN,
     * so the existence of the project is not revealed.
     */
    public ProjectRole requireMember(CurrentUser viewer, UUID projectId) {
        return members.findByProjectIdAndUserId(projectId, viewer.id())
                .map(ProjectMember::getRole)
                .orElseThrow(ApiException::notFound);
    }

    public void requireLead(CurrentUser viewer, UUID projectId) {
        if (requireMember(viewer, projectId) != ProjectRole.PROJECT_LEAD) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "Only the project lead can do this");
        }
    }

    /** Postgres array literal, e.g. {"engineering","design"}. */
    static String toPgArray(Collection<String> values) {
        return values.stream()
                .map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .sorted()
                .collect(Collectors.joining(",", "{", "}"));
    }
}
