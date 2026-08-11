package com.sentinelops.agents;

/**
 * DTO imutável que representa a resposta estruturada do Gemini
 * para uma análise de triagem de risco financeiro.
 *
 * <p>O {@link com.sentinelops.agents.TriageAgent} instrui o Gemini a retornar
 * estritamente este schema JSON. O {@link com.fasterxml.jackson.databind.ObjectMapper}
 * desserializa a resposta bruta neste Record.
 *
 * <h2>Schema JSON Esperado</h2>
 * <pre>
 * {
 *   "decision":  "APPROVED" | "BLOCKED" | "MANUAL_REVIEW",
 *   "riskScore": 0.0 a 1.0,
 *   "reason":    "Explicação detalhada da decisão"
 * }
 * </pre>
 *
 * <h2>Interpretação do riskScore</h2>
 * <ul>
 *   <li>{@code 0.0 – 0.3} → Risco Baixo → decisão esperada: APPROVED</li>
 *   <li>{@code 0.3 – 0.7} → Risco Médio → decisão esperada: MANUAL_REVIEW</li>
 *   <li>{@code 0.7 – 1.0} → Risco Alto  → decisão esperada: BLOCKED</li>
 * </ul>
 *
 * @param decision  decisão tomada pelo LLM (mapeada para {@link com.sentinelops.core.TransactionStatus})
 * @param riskScore pontuação de risco normalizada entre 0.0 e 1.0
 * @param reason    explicação legível por humanos para a decisão
 */
public record TriageDecision(
        String decision,
        double riskScore,
        String reason
) {
    /**
     * Compact constructor com validação defensiva.
     * Garante que valores inválidos do LLM não corrompam o pipeline.
     */
    public TriageDecision {
        if (decision == null || decision.isBlank()) {
            decision = "MANUAL_REVIEW";
        }
        // Normaliza riskScore para o intervalo [0.0, 1.0]
        riskScore = Math.max(0.0, Math.min(1.0, riskScore));
        if (reason == null || reason.isBlank()) {
            reason = "Sem justificativa fornecida pelo modelo.";
        }
    }

    /**
     * Retorna {@code true} se a decisão é de alto risco (BLOCKED).
     */
    public boolean isBlocked()       { return "BLOCKED".equalsIgnoreCase(decision); }

    /**
     * Retorna {@code true} se a decisão é de aprovação automática.
     */
    public boolean isApproved()      { return "APPROVED".equalsIgnoreCase(decision); }

    /**
     * Retorna {@code true} se requer escalada para revisão humana ou ForensicAgent.
     */
    public boolean isManualReview()  { return "MANUAL_REVIEW".equalsIgnoreCase(decision); }
}
