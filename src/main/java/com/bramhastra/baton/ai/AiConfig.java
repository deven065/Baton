package com.bramhastra.baton.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

/**
 * Chooses the LLM: Groq when a key is set and mock mode is off; otherwise offline mode, which first
 * replays any recorded Groq answer for the exact same prompt and falls back to the rule-based extractor.
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    LlmClient llmClient(@Value("${baton.ai.mock:true}") boolean mock,
                        @Value("${baton.groq.api-key:}") String apiKey,
                        @Value("${baton.groq.base-url}") String baseUrl,
                        ObjectMapper json, LlmCache cache, OfflineExtractor offline) {
        if (!mock && apiKey != null && !apiKey.isBlank()) {
            log.info("AI mode: Groq ({})", baseUrl);
            return new GroqClient(baseUrl, apiKey, json, cache);
        }
        log.info("AI mode: offline (recorded responses, then rule-based extraction)");
        return req -> cache.get(LlmCache.key(req))
                .map(content -> new LlmClient.LlmResponse(content, req.model(), "cache", 0, 0))
                .orElseGet(() -> offline.complete(req));
    }
}
