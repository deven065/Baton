package com.bramhastra.baton.review;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.handover.Handover;
import com.bramhastra.baton.handover.HandoverAccess;
import com.bramhastra.baton.identity.AppUserRepository;
import com.bramhastra.baton.identity.ProjectMemberRepository;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.security.CurrentUserProvider;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class ReviewController {

    private final ClaimReviewService review;
    private final TestimonyService testimony;
    private final ClaimQueryService query;
    private final HandoverAccess handoverAccess;
    private final CurrentUserProvider currentUser;
    private final AppUserRepository users;
    private final ProjectMemberRepository members;
    private final AuditService audit;

    public ReviewController(ClaimReviewService review, TestimonyService testimony, ClaimQueryService query,
                            HandoverAccess handoverAccess, CurrentUserProvider currentUser, AppUserRepository users,
                            ProjectMemberRepository members, AuditService audit) {
        this.review = review;
        this.testimony = testimony;
        this.query = query;
        this.handoverAccess = handoverAccess;
        this.currentUser = currentUser;
        this.users = users;
        this.members = members;
        this.audit = audit;
    }

    /** Accept, edit, reject, needs-info or restore. Must send the claim's current version. */
    @PatchMapping("/claims/{claimId}")
    public ClaimQueryService.ClaimView patch(@PathVariable UUID claimId, @RequestBody ClaimReviewService.Patch patch) {
        return review.patch(currentUser.get(), claimId, patch);
    }

    @GetMapping("/claims/{claimId}/revisions")
    public List<ClaimReviewService.RevisionView> revisions(@PathVariable UUID claimId) {
        return review.revisions(currentUser.get(), claimId);
    }

    /** Resolve a conflict: this claim is current, {@code otherClaimId} is superseded. */
    @PostMapping("/claims/{claimId}/resolve-conflict")
    public ClaimQueryService.ClaimView resolve(@PathVariable UUID claimId, @Valid @RequestBody Resolve req) {
        return review.resolveConflict(currentUser.get(), claimId, req.otherClaimId(), req.reason());
    }

    @PostMapping("/handovers/{handoverId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimQueryService.ClaimView add(@PathVariable UUID handoverId, @RequestBody ClaimReviewService.NewClaim req) {
        return review.addClaim(currentUser.get(), handoverId, req);
    }

    /** Exit interview: only the departing member answers. */
    @PostMapping("/gaps/{gapId}/answer")
    public ClaimQueryService.GapView answer(@PathVariable UUID gapId, @RequestBody TestimonyService.Answer req) {
        return testimony.answer(currentUser.get(), gapId, req);
    }

    @PostMapping("/gaps/{gapId}/skip")
    public ClaimQueryService.GapView skip(@PathVariable UUID gapId, @RequestBody(required = false) Map<String, String> body) {
        return testimony.skip(currentUser.get(), gapId, body == null ? null : body.get("reason"));
    }

    /**
     * "View as": the lead sees exactly which claims a recipient would see, using the recipient's own
     * clearance and groups. Rejected claims are never shown to recipients.
     */
    @GetMapping("/handovers/{handoverId}/preview")
    public Map<String, Object> preview(@PathVariable UUID handoverId, @RequestParam("as") UUID asUserId) {
        CurrentUser me = currentUser.get();
        Handover h = handoverAccess.requireLead(me, handoverId);
        if (members.findByProjectIdAndUserId(h.projectId(), asUserId).isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed",
                    Map.of("as", "Must be a member of this project"));
        }
        CurrentUser as = users.findById(asUserId).map(CurrentUser::of).orElseThrow(ApiException::notFound);
        List<ClaimQueryService.ClaimView> visible = query.list(handoverId, as,
                new ClaimQueryService.Filter(null, null, null, null), 0, 500).items().stream()
                .filter(c -> !c.reviewState().equals("REJECTED")).toList();
        long leadSees = query.list(handoverId, me, new ClaimQueryService.Filter(null, null, null, null), 0, 500)
                .items().stream().filter(c -> !c.reviewState().equals("REJECTED")).count();
        long hidden = leadSees - visible.size();
        audit.record(me.id(), "PREVIEW_AS", "handover", h.id(), h.projectId(), Map.of("as", asUserId.toString()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("as", Map.of("id", as.id(), "displayName", as.displayName(), "clearance", as.clearance(),
                "groups", as.groups()));
        out.put("visibleCount", visible.size());
        out.put("hiddenFromThemCount", Math.max(0, hidden));
        out.put("claims", visible);
        return out;
    }

    public record Resolve(@NotNull UUID otherClaimId, String reason) {
    }
}
