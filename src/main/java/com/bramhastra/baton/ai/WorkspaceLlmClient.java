package com.bramhastra.baton.ai;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.bramhastra.baton.tenant.Tenant;
import com.bramhastra.baton.tenant.TenantContext;

import tools.jackson.databind.ObjectMapper;

/**
 * Routes each model call to the current workspace's own provider when it has one switched on; otherwise to
 * Baton's default (offline rules, or the server's own key if one is configured). One client per workspace,
 * so one company's rate limits and outages never affect another's.
 */
@Component
@Primary
public class WorkspaceLlmClient implements LlmClient {

    private final AiSettingsService settings;
    private final LlmClient fallback;
    private final LlmCache cache;
    private final ObjectMapper json;
    private final Map<UUID, Entry> clients = new ConcurrentHashMap<>();

    private record Entry(String fingerprint, LlmClient client) {
    }

    public WorkspaceLlmClient(AiSettingsService settings, @Qualifier("llmClient") LlmClient fallback, LlmCache cache,
                              ObjectMapper json) {
        this.settings = settings;
        this.fallback = fallback;
        this.cache = cache;
        this.json = json;
    }

    @Override
    public LlmResponse complete(LlmRequest req) {
        Optional<Tenant> tenant = TenantContext.current();
        Optional<AiSettingsService.Active> own = tenant.flatMap(t -> settings.active());
        if (tenant.isEmpty() || own.isEmpty()) {
            return fallback.complete(req);
        }
        AiSettingsService.Active a = own.get();
        LlmClient client = clientFor(tenant.get().id(), a);
        LlmRequest mine = new LlmRequest(req.promptId(), a.model(), req.system(), req.user(), req.temperature(),
                req.maxTokens(), req.jsonMode());
        try {
            LlmResponse r = client.complete(mine);
            if (!"cache".equals(r.source())) {
                settings.recordCall(a.provider(), a.model(), req.promptId(), r.tokensIn(), r.tokensOut(), true);
            }
            return r;
        } catch (LlmUnavailableException | LlmClientError e) {
            settings.recordCall(a.provider(), a.model(), req.promptId(), 0, 0, false);
            throw e;
        }
    }

    /** A direct call with given settings, for the admin's "Test connection" button. Sends no company data. */
    public LlmResponse probe(AiSettingsService.Active a) {
        LlmClient client = new GroqClient(a.baseUrl(), a.apiKey(), json, noCache(), label(a));
        return client.complete(new LlmRequest("connection-test", a.model(),
                "You are a connectivity check. Reply with exactly: OK", "Reply with OK.", 0.0, 5, false));
    }

    private LlmClient clientFor(UUID tenantId, AiSettingsService.Active a) {
        String fp = a.provider() + "|" + a.baseUrl() + "|" + a.updatedAt();
        Entry e = clients.compute(tenantId, (id, old) ->
                old != null && old.fingerprint().equals(fp) ? old
                        : new Entry(fp, new GroqClient(a.baseUrl(), a.apiKey(), json, cache, label(a))));
        return e.client();
    }

    private static String label(AiSettingsService.Active a) {
        return a.provider().name().toLowerCase(Locale.ROOT);
    }

    /** The connection test must not read or write the workspace's response cache. */
    private static LlmCache noCache() {
        return new LlmCache(null) {
            @Override
            public Optional<String> get(String key) {
                return Optional.empty();
            }

            @Override
            public void put(String key, LlmRequest r, LlmResponse response) {
                // not cached
            }
        };
    }
}
