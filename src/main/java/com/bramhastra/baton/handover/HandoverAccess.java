package com.bramhastra.baton.handover;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.identity.ProjectRole;
import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.security.CurrentUser;

/**
 * Who may open a handover's working area (claims under review, gaps, timeline): the project lead and the
 * departing member. Recipients only ever see approved, shared versions (M5).
 * Non-members get 404; other members get FORBIDDEN_ROLE.
 */
@Component
public class HandoverAccess {

    private final HandoverRepository handovers;
    private final AccessPolicy access;

    public HandoverAccess(HandoverRepository handovers, AccessPolicy access) {
        this.handovers = handovers;
        this.access = access;
    }

    public Handover requireWorkspace(CurrentUser viewer, UUID handoverId) {
        Handover h = handovers.findById(handoverId).orElseThrow(ApiException::notFound);
        ProjectRole role = access.requireMember(viewer, h.projectId());
        if (role != ProjectRole.PROJECT_LEAD && !viewer.id().equals(h.departingUserId())) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "Only the lead and the departing member can open this");
        }
        return h;
    }

    public Handover requireLead(CurrentUser viewer, UUID handoverId) {
        Handover h = handovers.findById(handoverId).orElseThrow(ApiException::notFound);
        access.requireLead(viewer, h.projectId());
        return h;
    }

    public boolean isLead(CurrentUser viewer, Handover h) {
        return access.requireMember(viewer, h.projectId()) == ProjectRole.PROJECT_LEAD;
    }
}
