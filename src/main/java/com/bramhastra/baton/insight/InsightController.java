package com.bramhastra.baton.insight;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bramhastra.baton.security.CurrentUserProvider;

@RestController
@RequestMapping("/api")
public class InsightController {

    private final RiskService risk;
    private final CurrentUserProvider currentUser;

    public InsightController(RiskService risk, CurrentUserProvider currentUser) {
        this.risk = risk;
        this.currentUser = currentUser;
    }

    /** Knowledge-risk radar for a project. Its lead or an organisation admin. */
    @GetMapping("/projects/{projectId}/risk")
    public RiskService.Radar radar(@PathVariable UUID projectId) {
        return risk.radar(currentUser.get(), projectId);
    }
}
