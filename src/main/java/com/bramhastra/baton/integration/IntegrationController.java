package com.bramhastra.baton.integration;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bramhastra.baton.admin.AdminService;
import com.bramhastra.baton.common.RateLimiter;
import com.bramhastra.baton.common.SafeLog;
import com.bramhastra.baton.integration.IntegrationService.Kind;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.security.CurrentUserProvider;
import com.bramhastra.baton.source.connector.GitHubConnector;
import com.bramhastra.baton.source.connector.GoogleDriveConnector;
import com.bramhastra.baton.source.connector.SlackConnector;
import com.bramhastra.baton.tenant.TenantContext;

/** Organisation → Connections: the workspace's own GitHub, Slack and Google Drive credentials. */
@RestController
@RequestMapping("/api")
public class IntegrationController {

    private final IntegrationService integrations;
    private final GitHubConnector github;
    private final SlackConnector slack;
    private final GoogleDriveConnector drive;
    private final CurrentUserProvider currentUser;
    private final RateLimiter limits;

    public IntegrationController(IntegrationService integrations, GitHubConnector github, SlackConnector slack,
                                 GoogleDriveConnector drive, CurrentUserProvider currentUser, RateLimiter limits) {
        this.integrations = integrations;
        this.github = github;
        this.slack = slack;
        this.drive = drive;
        this.currentUser = currentUser;
        this.limits = limits;
    }

    /** Which tools are connected (no secrets). Any signed-in person: leads need it to add sources. */
    @GetMapping("/integrations")
    public List<IntegrationService.View> list() {
        currentUser.get();
        return integrations.list();
    }

    @PutMapping("/admin/integrations/{kind}")
    public IntegrationService.View save(@PathVariable Kind kind, @RequestBody Map<String, String> body) {
        CurrentUser me = admin();
        return integrations.save(me.id(), kind, body.get("secret"));
    }

    @DeleteMapping("/admin/integrations/{kind}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Kind kind) {
        integrations.remove(admin().id(), kind);
    }

    /** Tries the given secret (or the stored one when none is given) against the tool. Reads nothing from it. */
    @PostMapping("/admin/integrations/{kind}/test")
    public Map<String, Object> test(@PathVariable Kind kind, @RequestBody(required = false) Map<String, String> body) {
        admin();
        limits.acquire("integration-test:" + TenantContext.require().id(), 15, Duration.ofMinutes(10));
        String given = body == null ? null : body.get("secret");
        String secret;
        if (given != null && !given.isBlank()) {
            integrations.hintFor(kind, given);
            secret = given.strip();
        } else {
            secret = integrations.require(kind);
        }
        try {
            String who = switch (kind) {
                case GITHUB -> "Signed in to GitHub as " + github.test(secret);
                case SLACK -> "Connected to the Slack workspace " + slack.test(secret);
                case GOOGLE_DRIVE -> {
                    drive.test(secret);
                    yield "Google accepted the service account";
                }
            };
            return Map.of("ok", true, "message", who);
        } catch (IllegalArgumentException e) {
            return Map.of("ok", false, "message", e.getMessage());
        } catch (RuntimeException e) {
            return Map.of("ok", false, "message", "Could not reach " + IntegrationService.label(kind) + " (" + SafeLog.describe(e).split(" ")[0] + ")");
        }
    }

    private CurrentUser admin() {
        CurrentUser me = currentUser.get();
        AdminService.requireAdmin(me);
        return me;
    }
}
