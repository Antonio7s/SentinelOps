package com.sentinelops.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Policy Gateway — camada de governança corporativa e Human-in-the-Loop.
 *
 * <p>Implementa o padrão <b>Agent Gateway</b>: toda decisão final de bloqueio
 * ou aprovação deve passar por este componente antes de ser persistida.
 * O gateway garante que nenhum agente de IA possa tomar decisões críticas
 * sem o aval das políticas de compliance da empresa.
 *
 * <h2>Políticas Implementadas</h2>
 *
 * <table border="1">
 *   <tr><th>Política</th><th>Condição</th><th>Override</th><th>Justificativa</th></tr>
 *   <tr>
 *     <td>POLICY_HIGH_VALUE_BLOCK</td>
 *     <td>amount ≥ R$10.000 AND proposta=BLOCKED</td>
 *     <td>→ MANUAL_REVIEW</td>
 *     <td>Transações de alto valor exigem aprovação humana</td>
 *   </tr>
 *   <tr>
 *     <td>POLICY_CRITICAL_RISK</td>
 *     <td>riskScore > 0.95</td>
 *     <td>→ mantém BLOCKED</td>
 *     <td>Risco extremo permite bloqueio automático imediato</td>
 *   </tr>
 *   <tr>
 *     <td>DEFAULT</td>
 *     <td>demais casos</td>
 *     <td>→ proposta da IA</td>
 *     <td>Delegação para decisão do agente</td>
 *   </tr>
 * </table>
 *
 * <h2>Motivação de Negócio</h2>
 * <p>O bloqueio automático de uma transação legítima de alto valor pode gerar
 * dano reputacional severo. Portanto, acima de R$10.000,00, mesmo com
 * risco alto (mas não crítico ≥0.95), um analista humano deve revisar.
 *
 * <h2>Thread Safety</h2>
 * <p>Este componente é stateless — nenhum estado mutável. Seguro para acesso
 * concorrente pelo {@link com.sentinelops.agents.ResolutionAgent}.
 */
@Slf4j
@Component
public class PolicyGateway {

    // ─────────────────────────────────────────────────────────────────────────
    // Constantes de Política
    // ─────────────────────────────────────────────────────────────────────────

    /** Valor mínimo (inclusive) que exige revisão humana para bloqueio automático. */
    public static final BigDecimal HIGH_VALUE_THRESHOLD = new BigDecimal("10000.00");

    /** Limiar de risco crítico que permite bloqueio automático mesmo em transações de alto valor. */
    public static final double CRITICAL_RISK_THRESHOLD = 0.95;

    // ─────────────────────────────────────────────────────────────────────────
    // API Pública
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Aplica as políticas corporativas à decisão proposta pelo agente de IA.
     *
     * <p>O método avalia as regras em ordem de prioridade decrescente:
     * <ol>
     *   <li><b>Regra 2 (SLA Crítico)</b> — verificada primeiro: risco extremo
     *       permite bloqueio automático mesmo acima do limiar de valor.</li>
     *   <li><b>Regra 1 (Teto de Automação)</b> — alto valor + BLOCKED → MANUAL_REVIEW.</li>
     *   <li><b>Regra 3 (Default)</b> — retorna a proposta da IA sem modificação.</li>
     * </ol>
     *
     * @param transaction      transação com os dados completos (especialmente {@code amount})
     * @param proposedDecision decisão proposta pelo agente ("APPROVED", "BLOCKED", "MANUAL_REVIEW")
     * @param riskScore        score de risco calculado pelo agente (0.0–1.0)
     * @return status de transação aprovado pelas políticas corporativas
     */
    public TransactionStatus enforce(Transaction transaction,
                                      String proposedDecision,
                                      double riskScore) {

        BigDecimal amount = transaction.getAmount() != null
                ? transaction.getAmount()
                : BigDecimal.ZERO;

        boolean isBlockProposal   = "BLOCKED".equalsIgnoreCase(proposedDecision);
        boolean isHighValue       = amount.compareTo(HIGH_VALUE_THRESHOLD) >= 0;
        boolean isCriticalRisk    = riskScore > CRITICAL_RISK_THRESHOLD;

        log.info("[PolicyGateway] Avaliando política | txId={} | amount={} | proposed={} | riskScore={} | " +
                 "isHighValue={} | isCriticalRisk={}",
                 transaction.getId(), amount, proposedDecision, riskScore,
                 isHighValue, isCriticalRisk);

        // ── Regra 2 (SLA Crítico): risco > 0.95 → bloqueio imediato autorizado ──
        if (isBlockProposal && isCriticalRisk) {
            log.warn("[PolicyGateway] ✓ POLICY_CRITICAL_RISK aplicada | " +
                     "riskScore={} > {:.2f} → BLOCKED autorizado | txId={}",
                     riskScore, CRITICAL_RISK_THRESHOLD, transaction.getId());
            return TransactionStatus.BLOCKED;
        }

        // ── Regra 1 (Teto de Automação): alto valor + BLOCKED → MANUAL_REVIEW ──
        if (isBlockProposal && isHighValue) {
            log.warn("[PolicyGateway] ⚡ POLICY_OVERRIDE aplicada | " +
                     "POLICY_OVERRIDE: Transactions above R$ 10.000,00 require human approval for blocking. " +
                     "amount={} ≥ {} | txId={} | proposta BLOCKED → MANUAL_REVIEW",
                     amount, HIGH_VALUE_THRESHOLD, transaction.getId());
            return TransactionStatus.MANUAL_REVIEW;
        }

        // ── Regra 3 (Default): delega decisão ao agente ──────────────────────
        TransactionStatus finalStatus = mapDecision(proposedDecision);
        log.info("[PolicyGateway] ✓ POLICY_DEFAULT | proposta '{}' aceita sem override → {} | txId={}",
                 proposedDecision, finalStatus, transaction.getId());
        return finalStatus;
    }

    /**
     * Retorna um resumo legível da política aplicada para logging e auditoria.
     *
     * @param transaction      transação avaliada
     * @param proposedDecision decisão original do agente
     * @param finalStatus      status aprovado pelo gateway
     * @param riskScore        score de risco
     * @return string descritiva para o campo {@code thoughtProcess} do AuditLog
     */
    public String buildPolicyJustification(Transaction transaction,
                                            String proposedDecision,
                                            TransactionStatus finalStatus,
                                            double riskScore) {
        BigDecimal amount    = transaction.getAmount() != null ? transaction.getAmount() : BigDecimal.ZERO;
        boolean overridden   = !mapDecision(proposedDecision).equals(finalStatus);

        if (overridden && finalStatus == TransactionStatus.MANUAL_REVIEW) {
            return String.format(
                "POLICY_HIGH_VALUE_BLOCK: proposed '%s' overridden to MANUAL_REVIEW. " +
                "Reason: amount=R$%s >= threshold=R$%s | riskScore=%.2f | " +
                "Policy: transactions above R$ 10.000,00 require human approval for blocking.",
                proposedDecision, amount.toPlainString(),
                HIGH_VALUE_THRESHOLD.toPlainString(), riskScore);
        }

        if (finalStatus == TransactionStatus.BLOCKED && riskScore > CRITICAL_RISK_THRESHOLD) {
            return String.format(
                "POLICY_CRITICAL_RISK: automatic block authorized. " +
                "Reason: riskScore=%.2f > critical_threshold=%.2f | amount=R$%s.",
                riskScore, CRITICAL_RISK_THRESHOLD, amount.toPlainString());
        }

        return String.format(
            "POLICY_DEFAULT: proposed '%s' accepted by corporate policy. " +
            "riskScore=%.2f | amount=R$%s.",
            proposedDecision, riskScore, amount.toPlainString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private TransactionStatus mapDecision(String decision) {
        if (decision == null) return TransactionStatus.MANUAL_REVIEW;
        return switch (decision.toUpperCase()) {
            case "APPROVED"      -> TransactionStatus.APPROVED;
            case "BLOCKED"       -> TransactionStatus.BLOCKED;
            case "MANUAL_REVIEW" -> TransactionStatus.MANUAL_REVIEW;
            default -> {
                log.warn("[PolicyGateway] Decisão desconhecida '{}' → MANUAL_REVIEW (safe default)", decision);
                yield TransactionStatus.MANUAL_REVIEW;
            }
        };
    }
}
