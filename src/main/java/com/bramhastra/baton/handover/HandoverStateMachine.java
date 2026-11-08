package com.bramhastra.baton.handover;

import static com.bramhastra.baton.handover.HandoverStatus.APPROVED;
import static com.bramhastra.baton.handover.HandoverStatus.ARCHIVED;
import static com.bramhastra.baton.handover.HandoverStatus.DRAFT;
import static com.bramhastra.baton.handover.HandoverStatus.IN_REVIEW;
import static com.bramhastra.baton.handover.HandoverStatus.RECONSTRUCTING;
import static com.bramhastra.baton.handover.HandoverStatus.SHARED;

import java.util.Map;
import java.util.Set;

import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;

/**
 * The only place handover status changes are decided (LLD L3). Anything else is INVALID_TRANSITION.
 */
public final class HandoverStateMachine {

    public enum Event { RECONSTRUCT, JOB_DONE, JOB_FAILED, APPROVE, FIRST_SHARE, EDIT_AFTER_APPROVAL, ARCHIVE }

    private record Rule(Set<HandoverStatus> from, HandoverStatus to) {
    }

    private static final Map<Event, Rule> RULES = Map.of(
            Event.RECONSTRUCT, new Rule(Set.of(DRAFT, IN_REVIEW), RECONSTRUCTING),
            Event.JOB_DONE, new Rule(Set.of(RECONSTRUCTING), IN_REVIEW),
            Event.JOB_FAILED, new Rule(Set.of(RECONSTRUCTING), DRAFT),
            Event.APPROVE, new Rule(Set.of(IN_REVIEW), APPROVED),
            Event.FIRST_SHARE, new Rule(Set.of(APPROVED), SHARED),
            Event.EDIT_AFTER_APPROVAL, new Rule(Set.of(APPROVED, SHARED), IN_REVIEW),
            Event.ARCHIVE, new Rule(Set.of(APPROVED, SHARED), ARCHIVED));

    private HandoverStateMachine() {
    }

    public static HandoverStatus next(HandoverStatus current, Event event) {
        Rule rule = RULES.get(event);
        if (rule == null || !rule.from().contains(current)) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION,
                    "Cannot " + event.name().toLowerCase().replace('_', ' ') + " a handover that is " + current);
        }
        return rule.to();
    }

    /** A failed job on a handover that had claims before goes back to review, not draft. */
    public static HandoverStatus afterFailure(boolean hadClaims) {
        return hadClaims ? IN_REVIEW : DRAFT;
    }
}
