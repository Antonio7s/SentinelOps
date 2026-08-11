package com.sentinelops.core;

/**
 * Ciclo de vida de uma {@link Transaction} no sistema SentinelOps.
 *
 * <pre>
 *                     ┌──────────────┐
 *   POST /api/v1/  ──►│   PENDING    │
 *   transactions      └──────┬───────┘
 *                            │  TriageAgent analisa
 *                  ┌─────────┼─────────┐
 *                  ▼         ▼         ▼
 *            APPROVED    BLOCKED   MANUAL_REVIEW
 *                                      │
 *                              Operador humano decide
 *                              (futuro: ForensicAgent)
 * </pre>
 */
public enum TransactionStatus {

    /**
     * Transação recebida, aguardando análise dos agentes.
     * Estado inicial definido pelo {@code TransactionIngestionService}.
     */
    PENDING,

    /**
     * Transação aprovada pelo pipeline de agentes — sem anomalias detectadas.
     */
    APPROVED,

    /**
     * Transação bloqueada por suspeita de fraude.
     * O ForensicAgent (fase futura) gera evidências para este estado.
     */
    BLOCKED,

    /**
     * Agentes não atingiram confiança suficiente para decisão automática.
     * Encaminhada para revisão humana.
     * Este estado também é definido pelo fallback do {@code GeminiApiClient}
     * quando a API está indisponível.
     */
    MANUAL_REVIEW
}
