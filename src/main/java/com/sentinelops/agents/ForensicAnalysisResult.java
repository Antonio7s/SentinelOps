package com.sentinelops.agents;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTO imutável com o resultado da análise forense de comportamento histórico.
 *
 * <p>Produzido pelo {@link ForensicAgent} após análise pelo Gemini e
 * persistido no {@link com.sentinelops.core.AgentAuditLog} com a tag
 * de identidade {@code FORENSIC_AGENT_01}.
 *
 * <h2>Schema JSON esperado do Gemini</h2>
 * <pre>
 * {
 *   "hasHistoricalAnomaly": true,
 *   "anomalyScore":         0.83,
 *   "detectedPatterns":     ["VALOR_ACIMA_DA_MEDIA_3X", "MUDANCA_BRUSCA_CATEGORIA"],
 *   "forensicSummary":      "Conta apresenta padrão atípico..."
 * }
 * </pre>
 *
 * @param hasHistoricalAnomaly {@code true} se o histórico revela anomalia comportamental
 * @param anomalyScore         pontuação de anomalia normalizada (0.0 = normal, 1.0 = anomalia máxima)
 * @param detectedPatterns     padrões suspeitos identificados pelo ForensicAgent
 * @param forensicSummary      sumário narrativo legível da análise forense
 * @param agentId              identidade do agente que executou a análise (Zero-Trust tag)
 * @param transactionId        UUID da transação analisada
 * @param analyzedAt           timestamp da análise forense
 */
public record ForensicAnalysisResult(
        boolean          hasHistoricalAnomaly,
        double           anomalyScore,
        List<String>     detectedPatterns,
        String           forensicSummary,

        // Metadados de identidade Zero-Trust
        String           agentId,
        UUID             transactionId,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime    analyzedAt
) {
    /**
     * Compact constructor: validação defensiva e normalização de valores do LLM.
     */
    public ForensicAnalysisResult {
        anomalyScore     = Math.max(0.0, Math.min(1.0, anomalyScore));
        detectedPatterns = detectedPatterns == null ? List.of() : List.copyOf(detectedPatterns);
        if (forensicSummary == null || forensicSummary.isBlank()) {
            forensicSummary = "Análise forense sem sumário gerado pelo modelo.";
        }
        if (analyzedAt == null) analyzedAt = LocalDateTime.now();
        if (agentId    == null) agentId    = "FORENSIC_AGENT_01";
    }

    /** {@code true} se anomalyScore ≥ 0.7 — limiar de alto risco forense. */
    public boolean isHighRisk() { return anomalyScore >= 0.7; }

    /** {@code true} se anomalyScore ≥ 0.4 — limiar de risco moderado. */
    public boolean isMediumRisk() { return anomalyScore >= 0.4 && anomalyScore < 0.7; }

    /**
     * Representa a visão anonimizada de uma transação histórica enviada ao Gemini.
     * Nunca contém o {@code accountId} real — apenas a versão mascarada.
     *
     * @param maskedAccountId   accountId mascarado (ex: "ACC-***23")
     * @param amount            valor TRUNCADO ao inteiro mais próximo (ex: R$4.850 → R$4.000) para reduzir precisão
     * @param merchantCategory  categoria do merchant (sem PII)
     * @param status            status da transação
     * @param timestamp         data/hora da transação
     * @param dayOfWeek         dia da semana (ex: "TUESDAY") para análise temporal sem revelar data exata
     * @param hourOfDay         hora do dia (0-23) para padrão temporal
     */
    public record AnonymizedTransaction(
            String        maskedAccountId,
            BigDecimal    amount,
            String        merchantCategory,
            String        status,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
            LocalDateTime timestamp,
            String        dayOfWeek,
            int           hourOfDay
    ) {}
}
