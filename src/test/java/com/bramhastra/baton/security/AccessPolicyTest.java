package com.bramhastra.baton.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.bramhastra.baton.common.Sensitivity;
import com.bramhastra.baton.identity.OrgRole;
import com.bramhastra.baton.identity.ProjectMemberRepository;

class AccessPolicyTest {

    private final AccessPolicy policy = new AccessPolicy(mock(ProjectMemberRepository.class));

    private static CurrentUser viewer(Sensitivity clearance, String... groups) {
        return new CurrentUser(UUID.randomUUID(), "x@annapurna.test", "X", OrgRole.MEMBER, clearance, Set.of(groups), false);
    }

    @Test
    void sensitivityAboveClearanceIsHidden() {
        CurrentUser rahul = viewer(Sensitivity.INTERNAL, "design");
        assertThat(policy.canSee(rahul, Sensitivity.INTERNAL, List.of())).isTrue();
        assertThat(policy.canSee(rahul, Sensitivity.CONFIDENTIAL, List.of())).isFalse();
    }

    @Test
    void aclGroupsMustOverlap() {
        CurrentUser rahul = viewer(Sensitivity.RESTRICTED, "design");
        assertThat(policy.canSee(rahul, Sensitivity.INTERNAL, List.of("operations"))).isFalse();
        assertThat(policy.canSee(rahul, Sensitivity.INTERNAL, List.of("operations", "design"))).isTrue();
    }

    @Test
    void claimInheritsMaxSensitivityOfEvidence() {
        assertThat(Sensitivity.max(List.of(Sensitivity.PUBLIC, Sensitivity.CONFIDENTIAL, Sensitivity.INTERNAL)))
                .isEqualTo(Sensitivity.CONFIDENTIAL);
    }

    @Test
    void pgArrayLiteralIsQuotedAndEscaped() {
        assertThat(AccessPolicy.toPgArray(Set.of())).isEqualTo("{}");
        assertThat(AccessPolicy.toPgArray(List.of("design", "a\"b"))).isEqualTo("{\"a\\\"b\",\"design\"}");
    }
}
