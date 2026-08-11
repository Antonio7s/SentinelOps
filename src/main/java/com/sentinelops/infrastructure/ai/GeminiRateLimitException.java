package com.sentinelops.infrastructure.ai;

/**
 * Exceção lançada quando a API do Gemini retorna HTTP 429 (Too Many Requests).
 *
 * <p>Esta exceção é reconhecida pelo {@link GeminiRateLimitPredicate} e
 * sinaliza ao Resilience4j que deve aplicar o Exponential Backoff Retry.
 */
public class GeminiRateLimitException extends RuntimeException {

    public GeminiRateLimitException(String message) {
        super(message);
    }

    public GeminiRateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
