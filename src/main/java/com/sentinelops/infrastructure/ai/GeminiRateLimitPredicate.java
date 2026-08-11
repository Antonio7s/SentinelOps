package com.sentinelops.infrastructure.ai;

import io.github.resilience4j.core.predicate.PredicateCreator;

import java.util.function.Predicate;

/**
 * Predicado que instrui o Resilience4j a aplicar o retry
 * <strong>somente</strong> quando a exceção for uma {@link GeminiRateLimitException}.
 *
 * <p>Configurado no {@code application.yml} em:
 * <pre>
 * resilience4j:
 *   retry:
 *     instances:
 *       geminiApiRetry:
 *         retry-exception-predicate: com.sentinelops.infrastructure.ai.GeminiRateLimitPredicate
 * </pre>
 *
 * <p>O Resilience4j instancia esta classe via reflexão, portanto ela deve ter
 * um construtor padrão (sem argumentos) e implementar {@link Predicate} de {@link Throwable}.
 */
public class GeminiRateLimitPredicate implements Predicate<Throwable> {

    /**
     * Retorna {@code true} se e somente se o throwable for (ou tiver como causa)
     * uma {@link GeminiRateLimitException}, indicando HTTP 429.
     *
     * @param throwable a exceção capturada durante a chamada
     * @return {@code true} para ativar o retry; {@code false} para propagar o erro
     */
    @Override
    public boolean test(Throwable throwable) {
        return PredicateCreator.createExceptionsPredicate(GeminiRateLimitException.class)
                .map(p -> p.test(throwable))
                .orElse(false);
    }
}
