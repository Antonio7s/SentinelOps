package com.sentinelops.infrastructure.ai;

/**
 * Exceção lançada para erros HTTP que NÃO devem ser retentados.
 *
 * <p>Exemplos: HTTP 400 (Bad Request), 401 (Unauthorized), 403 (Forbidden),
 * 404 (Not Found) e erros 5xx de servidor.
 *
 * <p>O Resilience4j está configurado para ignorar esta exceção na política
 * de retry ({@code ignore-exceptions}), permitindo que o erro se propague
 * imediatamente sem tentativas desnecessárias.
 */
public class GeminiNonRetryableException extends RuntimeException {

    public GeminiNonRetryableException(String message) {
        super(message);
    }

    public GeminiNonRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
