package com.bramhastra.baton.review;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.handover.HandoverAccess;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.security.CurrentUserProvider;

@RestController
@RequestMapping("/api")
public class ClaimController {

    private static final Set<String> TYPES = Set.of("DECISION", "OPEN_WORK", "OWNER", "RISK", "DEPENDENCY", "BLOCKER",
            "RECURRING_ISSUE", "CONTACT", "OPEN_QUESTION", "PROCESS", "ACCESS");
    private static final Set<String> STATUSES = Set.of("VERIFIED", "SINGLE_SOURCE", "INFERRED", "CONFLICTING",
            "MISSING_EVIDENCE", "STALE");
    private static final Set<String> REVIEW = Set.of("PENDING", "ACCEPTED", "EDITED", "REJECTED", "NEEDS_INFO");

    private final ClaimQueryService query;
    private final HandoverAccess handoverAccess;
    private final CurrentUserProvider currentUser;
    private final JdbcClient jdbc;

    public ClaimController(ClaimQueryService query, HandoverAccess handoverAccess, CurrentUserProvider currentUser,
                           JdbcClient jdbc) {
        this.query = query;
        this.handoverAccess = handoverAccess;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
    }

    @GetMapping("/handovers/{handoverId}/claims")
    public ClaimQueryService.Page<ClaimQueryService.ClaimView> claims(
            @PathVariable UUID handoverId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String reviewState,
            @RequestParam(required = false) Boolean critical,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        CurrentUser me = currentUser.get();
        handoverAccess.requireWorkspace(me, handoverId);
        check("type", type, TYPES);
        check("status", status, STATUSES);
        check("reviewState", reviewState, REVIEW);
        return query.list(handoverId, me, new ClaimQueryService.Filter(type, status, reviewState, critical),
                Math.max(0, page), Math.max(1, Math.min(size, 200)));
    }

    @GetMapping("/claims/{claimId}")
    public ClaimQueryService.ClaimView claim(@PathVariable UUID claimId) {
        CurrentUser me = currentUser.get();
        UUID handoverId = jdbc.sql("SELECT handover_id FROM claim WHERE id = :id").param("id", claimId)
                .query(UUID.class).optional().orElseThrow(ApiException::notFound);
        handoverAccess.requireWorkspace(me, handoverId);
        return query.get(claimId, me).orElseThrow(ApiException::notFound);
    }

    @GetMapping("/handovers/{handoverId}/gaps")
    public List<ClaimQueryService.GapView> gaps(@PathVariable UUID handoverId) {
        CurrentUser me = currentUser.get();
        handoverAccess.requireWorkspace(me, handoverId);
        return query.gaps(handoverId, me);
    }

    @GetMapping("/handovers/{handoverId}/timeline")
    public List<ClaimQueryService.TimelineView> timeline(@PathVariable UUID handoverId) {
        CurrentUser me = currentUser.get();
        handoverAccess.requireWorkspace(me, handoverId);
        return query.timeline(handoverId, me);
    }

    @GetMapping("/handovers/{handoverId}/summary")
    public Map<String, Object> summary(@PathVariable UUID handoverId) {
        CurrentUser me = currentUser.get();
        handoverAccess.requireWorkspace(me, handoverId);
        return query.summary(handoverId, me);
    }

    private static void check(String field, String value, Set<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    Map.of(field, "Must be one of " + allowed));
        }
    }
}
