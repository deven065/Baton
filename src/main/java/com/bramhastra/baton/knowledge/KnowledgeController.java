package com.bramhastra.baton.knowledge;

import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bramhastra.baton.security.CurrentUserProvider;

/** The project's always-on knowledge: its decision log (with the why) and how capture is going. */
@RestController
@RequestMapping("/api")
public class KnowledgeController {

    private final KnowledgeService knowledge;
    private final ExpertService experts;
    private final RiskAlertService alerts;
    private final ProjectAskService ask;
    private final CurrentUserProvider currentUser;

    public KnowledgeController(KnowledgeService knowledge, ExpertService experts, RiskAlertService alerts, ProjectAskService ask,
                               CurrentUserProvider currentUser) {
        this.knowledge = knowledge;
        this.experts = experts;
        this.alerts = alerts;
        this.ask = ask;
        this.currentUser = currentUser;
    }

    /** "Who understands billing?": a ranked list with evidence, and whether the top name is a single point of failure. */
    @GetMapping("/projects/{projectId}/experts")
    public ExpertService.Experts experts(@PathVariable UUID projectId, @RequestParam String q,
                                         @RequestParam(defaultValue = "8") int limit) {
        return experts.find(currentUser.get(), projectId, q, limit);
    }

    /** Knowledge-risk alerts: who is the only person to have touched what, and who is leaving. Lead or admin. */
    @GetMapping("/projects/{projectId}/risk/alerts")
    public RiskAlertService.Alerts riskAlerts(@PathVariable UUID projectId, @RequestParam(defaultValue = "180") int days) {
        return alerts.alerts(currentUser.get(), projectId, days);
    }

    /** A question answered from everything the project knows, with citations, or an honest "I don't know, ask …". */
    @PostMapping("/projects/{projectId}/ask")
    public ProjectAskService.Answer ask(@PathVariable UUID projectId, @RequestBody Map<String, String> body) {
        return ask.ask(currentUser.get(), projectId, body == null ? null : body.get("question"));
    }

    /** Decisions the viewer may see, newest first; filter by words, topic or who decided. Any project member. */
    @GetMapping("/projects/{projectId}/decisions")
    public KnowledgeService.Log decisions(@PathVariable UUID projectId, @RequestParam(required = false) String q,
                                          @RequestParam(required = false) String topic, @RequestParam(required = false) String person,
                                          @RequestParam(defaultValue = "false") boolean rejected,
                                          @RequestParam(defaultValue = "100") int limit) {
        return knowledge.log(currentUser.get(), projectId, q, topic, person, rejected, limit);
    }

    /** The lead confirms a decision, rejects it as not a decision, or puts it back to undecided. */
    @PatchMapping("/decisions/{id}")
    public Map<String, String> review(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        knowledge.review(currentUser.get(), id, body == null ? null : body.get("review"));
        return Map.of("review", body.get("review"));
    }

    /** What capture is watching, and what it has found. Any project member. */
    @GetMapping("/projects/{projectId}/capture")
    public KnowledgeService.Capture capture(@PathVariable UUID projectId) {
        return knowledge.capture(currentUser.get(), projectId);
    }

    /** Reads every source again for decisions, without fetching anything. Lead only. */
    @PostMapping("/projects/{projectId}/capture/scan")
    public Map<String, Integer> scan(@PathVariable UUID projectId) {
        return Map.of("decisions", knowledge.mineAll(currentUser.get(), projectId));
    }
}
