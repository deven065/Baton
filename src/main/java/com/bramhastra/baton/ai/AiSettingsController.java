package com.bramhastra.baton.ai;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bramhastra.baton.admin.AdminService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.common.RateLimiter;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.security.CurrentUserProvider;
import com.bramhastra.baton.tenant.TenantContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Organisation → AI: the workspace's own AI provider. Admins only. */
@RestController
@RequestMapping("/api/admin/ai")
public class AiSettingsController {

    private final AiSettingsService settings;
    private final WorkspaceLlmClient llm;
    private final CurrentUserProvider currentUser;
    private final RateLimiter limits;

    public AiSettingsController(AiSettingsService settings, WorkspaceLlmClient llm, CurrentUserProvider currentUser,
                                RateLimiter limits) {
        this.settings = settings;
        this.llm = llm;
        this.currentUser = currentUser;
        this.limits = limits;
    }

    private CurrentUser admin() {
        CurrentUser me = currentUser.get();
        AdminService.requireAdmin(me);
        return me;
    }

    public record Overview(AiSettingsService.View settings, AiSettingsService.Usage usage) {
    }

    @GetMapping
    public Overview get() {
        admin();
        return new Overview(settings.view(), settings.usage());
    }

    @PutMapping
    public AiSettingsService.View save(@Valid @RequestBody SaveRequest req) {
        CurrentUser me = admin();
        return settings.save(me.id(), req.provider(), req.baseUrl(), req.model(), req.apiKey(),
                req.enabled() == null || req.enabled());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove() {
        settings.remove(admin().id());
    }

    /** Sends a two-word prompt (no company data) to check the address, key and model. */
    @PostMapping("/test")
    public Map<String, Object> test(@Valid @RequestBody SaveRequest req) {
        admin();
        limits.acquire("ai-test:" + TenantContext.require().id(), 10, java.time.Duration.ofMinutes(10));
        AiSettingsService.Active a = settings.resolve(req.provider(), req.baseUrl(), req.model(), req.apiKey());
        String key = a.apiKey() != null && !a.apiKey().isEmpty() ? a.apiKey()
                : settings.storedKey().orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Request validation failed", Map.of("apiKey", "Paste your API key")));
        long start = System.nanoTime();
        try {
            LlmClient.LlmResponse r = llm.probe(new AiSettingsService.Active(a.provider(), a.baseUrl(), a.model(), key, null));
            return Map.of("ok", true, "model", a.model(), "latencyMs", (System.nanoTime() - start) / 1_000_000,
                    "reply", r.content() == null ? "" : r.content().strip().substring(0, Math.min(20, r.content().strip().length())));
        } catch (LlmClient.LlmClientError | LlmClient.LlmUnavailableException e) {
            return Map.of("ok", false, "model", a.model(), "message", e.getMessage());
        }
    }

    public record SaveRequest(@NotNull AiSettingsService.Provider provider, @Size(max = 300) String baseUrl,
                              @Size(max = 120) String model, @Size(max = 400) String apiKey, Boolean enabled) {
    }
}
