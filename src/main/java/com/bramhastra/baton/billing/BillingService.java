package com.bramhastra.baton.billing;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.bramhastra.baton.audit.AuditService;
import com.bramhastra.baton.common.ApiException;
import com.bramhastra.baton.common.ErrorCode;
import com.bramhastra.baton.common.SafeLog;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.tenant.Tenant;
import com.bramhastra.baton.tenant.TenantContext;
import com.bramhastra.baton.tenant.TenantRegistry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Plans and limits. FREE is for trying Baton with a small team; TEAM is paid monthly through Razorpay payment
 * links (no card details ever touch Baton). Limits only stop new things being added; nothing is ever deleted or
 * hidden when a plan lapses. The demo workspace has no limits.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    public enum Plan { FREE, TEAM }

    public enum Resource {
        PEOPLE("people", "SELECT count(*) FROM app_user WHERE active"),
        PROJECTS("projects", "SELECT count(*) FROM project"),
        SOURCES("sources", "SELECT count(*) FROM source WHERE type <> 'TESTIMONY'");

        final String label;
        final String countSql;

        Resource(String label, String countSql) {
            this.label = label;
            this.countSql = countSql;
        }
    }

    public record Limits(int people, int projects, int sources) {
        int of(Resource r) {
            return switch (r) {
                case PEOPLE -> people;
                case PROJECTS -> projects;
                case SOURCES -> sources;
            };
        }
    }

    public record Overview(Plan plan, Instant paidUntil, boolean unlimited, Limits limits, Map<String, Integer> usage,
                           Limits freeLimits, Limits teamLimits, long teamPriceMinor, String currency,
                           boolean paymentsEnabled) {
    }

    private final TenantRegistry tenants;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Limits free;
    private final Limits team;
    private final long priceMinor;
    private final String currency;
    private final String keyId;
    private final String keySecret;
    private final String webhookSecret;
    private final String apiUrl;
    private final String appUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public BillingService(TenantRegistry tenants, JdbcClient jdbc, AuditService audit, ObjectMapper json,
                          @Value("${baton.billing.free.people:5}") int freePeople,
                          @Value("${baton.billing.free.projects:1}") int freeProjects,
                          @Value("${baton.billing.free.sources:10}") int freeSources,
                          @Value("${baton.billing.team.people:100}") int teamPeople,
                          @Value("${baton.billing.team.projects:50}") int teamProjects,
                          @Value("${baton.billing.team.sources:1000}") int teamSources,
                          @Value("${baton.billing.team.price-minor:499900}") long priceMinor,
                          @Value("${baton.billing.currency:INR}") String currency,
                          @Value("${baton.billing.razorpay.key-id:}") String keyId,
                          @Value("${baton.billing.razorpay.key-secret:}") String keySecret,
                          @Value("${baton.billing.razorpay.webhook-secret:}") String webhookSecret,
                          @Value("${baton.billing.razorpay.api-url:https://api.razorpay.com/v1}") String apiUrl,
                          @Value("${baton.app-url:http://localhost:5173}") String appUrl) {
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
        this.free = new Limits(freePeople, freeProjects, freeSources);
        this.team = new Limits(teamPeople, teamProjects, teamSources);
        this.priceMinor = priceMinor;
        this.currency = currency;
        this.keyId = keyId.strip();
        this.keySecret = keySecret.strip();
        this.webhookSecret = webhookSecret.strip();
        this.apiUrl = apiUrl.replaceAll("/+$", "");
        this.appUrl = appUrl.replaceAll("/+$", "");
        log.info("Payments: {}", paymentsEnabled() ? "on (Razorpay)" : "off (set RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET and RAZORPAY_WEBHOOK_SECRET)");
    }

    public boolean paymentsEnabled() {
        return !keyId.isEmpty() && !keySecret.isEmpty() && !webhookSecret.isEmpty();
    }

    private Plan planOf(Tenant t) {
        Instant until = tenants.paidUntil(t.id());
        return until != null && until.isAfter(Instant.now()) ? Plan.TEAM : Plan.FREE;
    }

    /** Refuses with 402 when the current workspace is at its plan's limit for {@code r}. Call before adding one. */
    public void requireRoom(Resource r) {
        Tenant t = TenantContext.require();
        if (t.usesHomeDb()) {
            return;
        }
        Plan plan = planOf(t);
        int limit = (plan == Plan.TEAM ? team : free).of(r);
        int used = jdbc.sql(r.countSql).query(Integer.class).single();
        if (used >= limit) {
            throw new ApiException(ErrorCode.PLAN_LIMIT, plan == Plan.FREE
                    ? "The Free plan allows " + limit + " " + r.label + ". Upgrade under Organisation → Billing to add more."
                    : "Your plan allows " + limit + " " + r.label + ". Contact us to raise the limit.",
                    Map.of("resource", r.name(), "limit", limit, "plan", plan.name()));
        }
    }

    public Overview overview() {
        Tenant t = TenantContext.require();
        Plan plan = planOf(t);
        Map<String, Integer> usage = new LinkedHashMap<>();
        for (Resource r : Resource.values()) {
            usage.put(r.name(), jdbc.sql(r.countSql).query(Integer.class).single());
        }
        return new Overview(plan, tenants.paidUntil(t.id()), t.usesHomeDb(), plan == Plan.TEAM ? team : free, usage,
                free, team, priceMinor, currency, paymentsEnabled());
    }

    /** Creates a Razorpay payment link for one month of TEAM and returns its URL. */
    public String checkout(CurrentUser me) {
        Tenant t = TenantContext.require();
        if (t.usesHomeDb()) {
            throw new ApiException(ErrorCode.NOT_ALLOWED, "The demo workspace has no plan to pay for");
        }
        if (!paymentsEnabled()) {
            throw new ApiException(ErrorCode.NOT_ALLOWED, "Online payment is not set up yet. Contact us to upgrade.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", priceMinor);
        body.put("currency", currency);
        body.put("description", "Baton Team plan, 1 month, workspace " + t.slug());
        body.put("reference_id", t.slug() + "-" + Long.toString(System.currentTimeMillis(), 36));
        body.put("customer", Map.of("name", me.displayName(), "email", me.email()));
        body.put("notify", Map.of("email", false, "sms", false));
        body.put("notes", Map.of("tenant", t.id().toString(), "slug", t.slug()));
        body.put("callback_url", appUrl + "/app/admin/billing?paid=1");
        body.put("callback_method", "get");
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl + "/payment_links")).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            String url = res.statusCode() / 100 == 2 ? json.readTree(res.body()).path("short_url").asString("") : "";
            if (url.isEmpty()) {
                log.warn("Razorpay payment link failed: HTTP {}", res.statusCode());
                throw new ApiException(ErrorCode.LLM_UNAVAILABLE, "The payment provider is not responding. Try again shortly.");
            }
            audit.record(me.id(), "BILLING_CHECKOUT_STARTED", "workspace", t.id(), null, Map.of("plan", "TEAM"));
            return url;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Razorpay payment link failed: {}", SafeLog.describe(e));
            throw new ApiException(ErrorCode.LLM_UNAVAILABLE, "The payment provider is not responding. Try again shortly.");
        }
    }

    /**
     * Razorpay webhook. The body is trusted only if its HMAC-SHA256 with the webhook secret matches the header.
     * A paid link extends the workspace by one month; each link counts once however often it is delivered.
     */
    public void webhook(String rawBody, String signature) {
        if (!paymentsEnabled() || signature == null || !MessageDigest.isEqual(
                hmac(webhookSecret, rawBody).getBytes(StandardCharsets.UTF_8), signature.strip().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Bad signature");
        }
        JsonNode root = json.readTree(rawBody);
        if (!"payment_link.paid".equals(root.path("event").asString(""))) {
            return;
        }
        JsonNode link = root.path("payload").path("payment_link").path("entity");
        String ref = link.path("id").asString("");
        long paid = link.path("amount_paid").asLong(0);
        UUID tenantId;
        try {
            tenantId = UUID.fromString(link.path("notes").path("tenant").asString(""));
        } catch (IllegalArgumentException e) {
            return;
        }
        if (ref.isEmpty() || !"paid".equals(link.path("status").asString("")) || paid < priceMinor
                || !currency.equalsIgnoreCase(link.path("currency").asString(""))) {
            log.warn("Ignored a payment webhook that does not match the plan price");
            return;
        }
        tenants.byId(tenantId).ifPresent(t -> {
            Instant until = tenants.recordPayment(t, ref, paid, currency);
            if (until != null) {
                TenantContext.run(t, () -> audit.record(null, "PLAN_PAID", "workspace", t.id(), null,
                        Map.of("plan", "TEAM", "paidUntil", until.toString())));
            }
        });
    }

    static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
