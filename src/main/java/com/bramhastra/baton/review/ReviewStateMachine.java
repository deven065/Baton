package com.bramhastra.baton.review;

import java.util.Map;
import java.util.Set;

import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;

/**
 * Claim review states (LLD L3):
 * PENDING → ACCEPTED | REJECTED | NEEDS_INFO | EDITED;  REJECTED → PENDING (restore);
 * NEEDS_INFO → PENDING (answer received);  ACCEPTED → EDITED (edit);  EDITED → ACCEPTED | EDITED.
 */
public final class ReviewStateMachine {

    public enum ReviewState { PENDING, ACCEPTED, EDITED, REJECTED, NEEDS_INFO }

    private static final Map<ReviewState, Set<ReviewState>> ALLOWED = Map.of(
            ReviewState.PENDING, Set.of(ReviewState.ACCEPTED, ReviewState.REJECTED, ReviewState.NEEDS_INFO, ReviewState.EDITED),
            ReviewState.REJECTED, Set.of(ReviewState.PENDING),
            ReviewState.NEEDS_INFO, Set.of(ReviewState.PENDING),
            ReviewState.ACCEPTED, Set.of(ReviewState.EDITED),
            ReviewState.EDITED, Set.of(ReviewState.ACCEPTED, ReviewState.EDITED));

    private ReviewStateMachine() {
    }

    public static void check(ReviewState from, ReviewState to) {
        if (!ALLOWED.get(from).contains(to)) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION,
                    "A claim that is " + from + " cannot become " + to,
                    Map.of("from", from.name(), "to", to.name(), "allowed", ALLOWED.get(from)));
        }
    }
}
