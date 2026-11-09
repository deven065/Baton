package com.bramhastra.baton.handover;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bramhastra.baton.security.CurrentUserProvider;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Approval, frozen versions, shares, and the recipient's view of a shared handover. */
@RestController
@RequestMapping("/api")
public class LifecycleController {

    private final ApprovalService approval;
    private final ShareService shares;
    private final CurrentUserProvider currentUser;

    public LifecycleController(ApprovalService approval, ShareService shares, CurrentUserProvider currentUser) {
        this.approval = approval;
        this.shares = shares;
        this.currentUser = currentUser;
    }

    // lead

    @GetMapping("/handovers/{id}/approval")
    public ApprovalService.Gate gate(@PathVariable UUID id) {
        return approval.gate(currentUser.get(), id);
    }

    /** Freezes a version. 409 APPROVAL_BLOCKED (details = blockers) while the gate is not passed. */
    @PostMapping("/handovers/{id}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    public ApprovalService.VersionView approve(@PathVariable UUID id) {
        return approval.approve(currentUser.get(), id);
    }

    @GetMapping("/handovers/{id}/versions")
    public List<ApprovalService.VersionView> versions(@PathVariable UUID id) {
        return approval.versions(currentUser.get(), id);
    }

    /** {@code to=0} compares the latest frozen version with the handover as it is now. */
    @GetMapping("/handovers/{id}/diff")
    public List<ApprovalService.Change> diff(@PathVariable UUID id, @RequestParam int from, @RequestParam(defaultValue = "0") int to) {
        return approval.diff(currentUser.get(), id, from, to);
    }

    @GetMapping("/handovers/{id}/shares")
    public List<ShareService.ShareRow> listShares(@PathVariable UUID id) {
        return shares.list(currentUser.get(), id);
    }

    @PostMapping("/handovers/{id}/shares")
    @ResponseStatus(HttpStatus.CREATED)
    public ShareService.ShareRow share(@PathVariable UUID id, @Valid @RequestBody ShareRequest req) {
        return shares.create(currentUser.get(), id, req.recipientId(), req.roleLabel(), req.expiresAt());
    }

    @PostMapping("/shares/{shareId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID shareId) {
        shares.revoke(currentUser.get(), shareId);
    }

    // recipient

    @GetMapping("/shares")
    public List<ShareService.MyShare> mine() {
        return shares.mine(currentUser.get());
    }

    @GetMapping("/shares/{shareId}")
    public ShareService.RecipientView view(@PathVariable UUID shareId) {
        return shares.view(currentUser.get(), shareId);
    }

    @PutMapping("/shares/{shareId}/checklist/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void check(@PathVariable UUID shareId, @PathVariable UUID itemId, @RequestBody Map<String, Boolean> body) {
        shares.setDone(currentUser.get(), shareId, itemId, Boolean.TRUE.equals(body.get("done")));
    }

    @PostMapping("/shares/{shareId}/acknowledge")
    public Map<String, Instant> acknowledge(@PathVariable UUID shareId) {
        return Map.of("acknowledgedAt", shares.acknowledge(currentUser.get(), shareId));
    }

    public record ShareRequest(@NotNull UUID recipientId, @Size(max = 60) String roleLabel, Instant expiresAt) {
    }
}
