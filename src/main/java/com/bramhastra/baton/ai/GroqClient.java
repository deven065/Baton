package com.bramhastra.baton.ai;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Groq chat completions (OpenAI-compatible), following LLD "GroqClient internals":
 * cache → circuit breaker → rate limiter → up to 4 attempts with back-off on 429/5xx.
 */
public class GroqClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GroqClient.class);
    private static final int ATTEMPTS = 4;

    private final RestClient http;
    private final ObjectMapper json;
    private final LlmCache cache;
    private final CircuitBreaker breaker;
    private final Semaphore limiter = new Semaphore(2);
    private final String label;

    public GroqClient(String baseUrl, String apiKey, ObjectMapper json, LlmCache cache) {
        this(baseUrl, apiKey, json, cache, "groq");
    }

    /** Any OpenAI-compatible chat completions API; {@code label} names it in responses and errors. */
    public GroqClient(String baseUrl, String apiKey, ObjectMapper json, LlmCache cache, String label) {
        this.label = label;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(90));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.json = json;
        this.cache = cache;
        this.breaker = new CircuitBreaker(4, Duration.ofSeconds(60), Clock.systemUTC());
    }

    @Override
    public LlmResponse complete(LlmRequest req) {
        String key = LlmCache.key(req);
        var cached = cache.get(key);
        if (cached.isPresent()) {
            return new LlmResponse(cached.get(), req.model(), "cache", 0, 0);
        }
        breaker.check();
        try {
            limiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("Interrupted");
        }
        try {
            String body = json.writeValueAsString(requestBody(req));
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                Result r = http.post().uri("/chat/completions").body(body).exchange((request, response) ->
                        new Result(response.getStatusCode().value(),
                                response.getHeaders().getFirst("retry-after"),
                                new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
                if (r.status() == 200) {
                    JsonNode root = json.readTree(r.body());
                    String content = root.path("choices").path(0).path("message").path("content").asString();
                    LlmResponse out = new LlmResponse(content, req.model(), label,
                            root.path("usage").path("prompt_tokens").asInt(),
                            root.path("usage").path("completion_tokens").asInt());
                    breaker.recordSuccess();
                    cache.put(key, req, out);
                    log.info("AI ({}) {} {} tokens in, {} out", label, req.promptId(), out.tokensIn(), out.tokensOut());
                    return out;
                }
                if (r.status() == 429 || r.status() >= 500) {
                    if (attempt < ATTEMPTS) {
                        sleep(backoffMillis(attempt, r.retryAfter()));
                    }
                    continue;
                }
                breaker.recordFailure();
                throw new LlmClientError("The AI provider (" + label + ") refused the request: HTTP " + r.status()
                        + (r.status() == 401 || r.status() == 403 ? " (check the API key)" : r.status() == 404 ? " (check the model name)" : ""));
            }
            breaker.recordFailure();
            throw new LlmUnavailableException("Waiting for AI capacity; " + label + " kept returning errors");
        } catch (org.springframework.web.client.ResourceAccessException e) {
            breaker.recordFailure();
            throw new LlmUnavailableException("Cannot reach the AI provider (" + label + ")");
        } finally {
            limiter.release();
        }
    }

    private Map<String, Object> requestBody(LlmRequest req) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", req.model());
        body.put("temperature", req.temperature());
        body.put("max_tokens", req.maxTokens());
        body.put("messages", List.of(
                Map.of("role", "system", "content", req.system()),
                Map.of("role", "user", "content", req.user())));
        if (req.jsonMode()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        return body;
    }

    static long backoffMillis(int attempt, String retryAfter) {
        if (retryAfter != null) {
            try {
                return (long) (Double.parseDouble(retryAfter) * 1000);
            } catch (NumberFormatException ignored) {
                // fall through to exponential
            }
        }
        return (1L << attempt) * 1000 + ThreadLocalRandom.current().nextLong(500);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(Math.min(ms, 30_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private record Result(int status, String retryAfter, String body) {
    }
}
