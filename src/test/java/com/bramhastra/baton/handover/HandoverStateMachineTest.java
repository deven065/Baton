package com.bramhastra.baton.handover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.handover.HandoverStateMachine.Event;

class HandoverStateMachineTest {

    @Test
    void happyPath() {
        HandoverStatus s = HandoverStatus.DRAFT;
        s = HandoverStateMachine.next(s, Event.RECONSTRUCT);
        s = HandoverStateMachine.next(s, Event.JOB_DONE);
        s = HandoverStateMachine.next(s, Event.APPROVE);
        s = HandoverStateMachine.next(s, Event.FIRST_SHARE);
        assertThat(s).isEqualTo(HandoverStatus.SHARED);
        assertThat(HandoverStateMachine.next(s, Event.EDIT_AFTER_APPROVAL)).isEqualTo(HandoverStatus.IN_REVIEW);
    }

    @Test
    void cannotApproveADraftOrReconstructWhileRunning() {
        assertThatThrownBy(() -> HandoverStateMachine.next(HandoverStatus.DRAFT, Event.APPROVE))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.INVALID_TRANSITION);
        assertThatThrownBy(() -> HandoverStateMachine.next(HandoverStatus.RECONSTRUCTING, Event.RECONSTRUCT))
                .isInstanceOf(ApiException.class);
    }
}
