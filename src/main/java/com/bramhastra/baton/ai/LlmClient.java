package com.bramhastra.baton.ai;

/** The only door to a language model. Callers send redacted text only, wrapped in untrusted-data tags. */
public interface LlmClient {

    LlmResponse complete(LlmRequest request);

    record LlmRequest(String promptId, String model, String system, String user, double temperature,
                      int maxTokens, boolean jsonMode) {
    }

    /** {@code source} is "groq", "cache" (replayed) or "offline" (rule-based, no network). */
    record LlmResponse(String content, String model, String source, int tokensIn, int tokensOut) {
    }

    class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message) {
            super(message);
        }
    }

    class LlmClientError extends RuntimeException {
        public LlmClientError(String message) {
            super(message);
        }
    }
}
