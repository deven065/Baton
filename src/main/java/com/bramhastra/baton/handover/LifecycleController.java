package com.bramhastra.baton.handover;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
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
    private final AskService ask;
    private final HandoverInsightService insight;
    private final ExportService exports;

    public LifecycleController(ApprovalService approval, ShareService shares, CurrentUserProvider currentUser,
                               AskService ask, HandoverInsightService insight, ExportService exports) {
        this.approval = approval;
        this.shares = shares;
        this.currentUser = currentUser;
        this.ask = ask;
        this.insight = insight;
        this.exports = exports;
    }

    // living handovers, progress, export (lead)

    @GetMapping("/handovers/{id}/drift")
    public HandoverInsightService.Drift drift(@PathVariable UUID id) {
        return insight.drift(currentUser.get(), id);
    }

    /** Starts the next version: the handover goes back to review; recipients keep the frozen one meanwhile. */
    @PostMapping("/handovers/{id}/reopen")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reopen(@PathVariable UUID id) {
        approval.reopen(currentUser.get(), id);
    }

    @PostMapping("/handovers/{id}/shares/move-to-latest")
    public Map<String, Integer> moveToLatest(@PathVariable UUID id) {
        return Map.of("moved", shares.moveToLatest(currentUser.get(), id));
    }

    @GetMapping("/handovers/{id}/progress")
    public List<HandoverInsightService.Successor> progress(@PathVariable UUID id) {
        return insight.progress(currentUser.get(), id);
    }

    @GetMapping("/handovers/{id}/versions/{versionNo}/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID id, @PathVariable int versionNo) {
        ExportService.Export e = exports.export(currentUser.get(), id, versionNo);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(e.filename()).build().toString())
                .body(e.zip());
    }

    @PostMapping(path = "/exports/verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExportService.Verification verify(@RequestParam("file") MultipartFile file) throws java.io.IOException {
        return exports.verify(currentUser.get(), file.getBytes());
    }

    // ask the handover (recipient)

    @PostMapping("/shares/{shareId}/ask")
    public AskService.Answer ask(@PathVariable UUID shareId, @RequestBody Map<String, String> body) {
        return ask.ask(currentUser.get(), shareId, body.get("question"));
    }

    @GetMapping("/shares/{shareId}/questions")
    public List<AskService.MyQuestion> myQuestions(@PathVariable UUID shareId) {
        return ask.mine(currentUser.get(), shareId);
    }

    /** Sends a question the handover could not answer to the person leaving. */
    @PostMapping("/shares/{shareId}/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public AskService.MyQuestion sendQuestion(@PathVariable UUID shareId, @RequestBody Map<String, String> body) {
        return ask.route(currentUser.get(), shareId, body.get("question"));
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
