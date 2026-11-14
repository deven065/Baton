package com.bramhastra.baton.staleness;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bramhastra.baton.security.AccessPolicy;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.security.CurrentUserProvider;

/** Stale documentation: what each project's docs still say that a later code change altered. Leads only. */
@RestController
@RequestMapping("/api")
public class StalenessController {

    private final StalenessService staleness;
    private final CurrentUserProvider currentUser;
    private final AccessPolicy access;

    public StalenessController(StalenessService staleness, CurrentUserProvider currentUser, AccessPolicy access) {
        this.staleness = staleness;
        this.currentUser = currentUser;
        this.access = access;
    }

    @GetMapping("/projects/{projectId}/staleness")
    public List<StalenessService.FindingView> list(@PathVariable UUID projectId) {
        return staleness.list(currentUser.get(), projectId);
    }

    @PostMapping("/projects/{projectId}/staleness/scan")
    public Map<String, Object> scan(@PathVariable UUID projectId) {
        CurrentUser me = currentUser.get();
        access.requireLead(me, projectId);
        staleness.scan(projectId);
        List<StalenessService.FindingView> visible = staleness.list(me, projectId);
        return Map.of("open", visible.stream().filter(f -> f.status().equals("OPEN")).count(), "findings", visible);
    }

    @PostMapping("/staleness/{findingId}/decision")
    public StalenessService.FindingView decide(@PathVariable UUID findingId, @RequestBody Map<String, String> body) {
        return staleness.decide(currentUser.get(), findingId, body == null ? null : body.get("status"));
    }
}
