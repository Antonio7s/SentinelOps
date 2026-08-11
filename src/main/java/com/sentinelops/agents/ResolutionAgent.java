package com.sentinelops.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.core.AgentAuditLog;
import com.sentinelops.core.AgentAuditLogRepository;
import com.sentinelops.core.AgentIdentity;
import com.sentinelops.core.PolicyGateway;
import com.sentinelops.core.Transaction;
import com.sentinelops.core.TransactionIngestionService;
import com.sentinelops.core.TransactionRepository;
import com.sentinelops.core.TransactionStatus;
import com.sentinelops.infrastructure.security.ModelArmorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Resolution Agent — árbitro final e camada de compliance do pipeline SentinelOps.
 *
 * <p>É sempre o <strong>último agente executado</strong> em qualquer pipeline de decisão.
 * Sua responsabilidade é garantir que nenhuma decisão da IA chegue ao banco de dados
 * sem antes passar pelo {@link PolicyGateway} (governança) e pelo {@link ModelArmorService}
 * (sanitização e inspeção de PII).
 *
 * <h2>Posição no Pipeline</h2>
 * <pre>
 *  TriageAgent (decide risco inicial)
 *       │
 *       ├── [MANUAL_REVIEW] → ForensicAgent (investiga histórico)
 *       │
 *       └── ResolutionAgent ← SEMPRE executado como etapa final
 *                │
 *                ├─ [1] ModelArmorService.sanitizeInput(reason)
 *                ├─ [2] PolicyGateway.enforce(transaction, proposedDecision, riskScore)
 *                ├─ [3] Atualiza status definitivo (se gateway sobrescreveu)
 *                └─ [4] AgentAuditLog com tag "RESOLUTION_AGENT_01" + justificativa de política
 * </pre>
 *
 * <h2>Identidade Zero-Trust</h2>
 * <p>Opera sob {@link AgentIdentity#RESOLUTION_AGENT} com permissões
 * {@code UPDATE_TRANSACTION_STATUS} e {@code WRITE_AUDIT_LOG}.
 * Não acessa histórico de transações — scope limitado à decisão final.
 *
 * <h2>Garantias do ResolutionAgent</h2>
 * <ol>
 *   <li>Toda decisão de bloqueio passa pelo PolicyGateway antes de persistir.</li>
 *   <li>Toda string de "reason" do agente anterior é sanitizada contra injeções.</li>
 *   <li>O AuditLog final inclui a justificativa de política aplicada.</li>
 *   <li>Falhas do ResolutionAgent não interrompem o pipeline — a transação
 *       fica com o status definido pelo agente anterior.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResolutionAgent {

    // ─────────────────────────────────────────────────────────────────────────
    // Identidade Zero-Trust
    // ─────────────────────────────────────────────────────────────────────────

    private static final AgentIdentity IDENTITY      = AgentIdentity.RESOLUTION_AGENT;
    static final         String         AGENT_NAME    = "ResolutionAgent";

    // ─────────────────────────────────────────────────────────────────────────
    // Dependências
    // ─────────────────────────────────────────────────────────────────────────

    private final ModelArmorService       modelArmorService;
    private final PolicyGateway           policyGateway;
    private final TransactionRepository   transactionRepository;
    private final AgentAuditLogRepository auditLogRepository;
    private final TransactionIngestionService ingestionService;
    private final ObjectMapper            objectMapper;

    // ─────────────────────────────────────────────────────────────────────────
    // API Pública
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Executa a resolução final de compliance de uma transação.
     *
     * <p>O método lê o status <em>atual</em> da transação no banco (já refletindo
     * decisões do TriageAgent e ForensicAgent), aplica as políticas corporativas
     * via {@link PolicyGateway#enforce}, e persiste o estado definitivo
     * com o AuditLog do {@code RESOLUTION_AGENT_01}.
     *
     * @param transactionId UUID da transação a finalizar
     * @param triageReason  razão textual fornecida pelo TriageAgent (sanitizada antes do uso)
     * @param riskScore     score de risco original do TriageAgent (0.0–1.0)
     */
    @Transactional
    public void executeFinalResolution(UUID transactionId, String triageReason, double riskScore) {
        log.info("[ResolutionAgent] ► Iniciando resolução final | txId={} | riskScore={} | identity={}",
                transactionId, riskScore, IDENTITY.agentId());

        // ── [Zero-Trust] Valida permissões ────────────────────────────────────
        IDENTITY.requirePermission(AgentIdentity.PERM_UPDATE_TRANSACTION_STATUS);
        IDENTITY.requirePermission(AgentIdentity.PERM_WRITE_AUDIT_LOG);

        // ── [1] Busca transação (com status pós-ForensicAgent) ────────────────
        Transaction transaction = transactionRepository.findById(transactionId).orElse(null);
        if (transaction == null) {
            log.error("[ResolutionAgent] Transação não encontrada — resolução abortada | txId={}", transactionId);
            return;
        }

        TransactionStatus currentStatus = transaction.getStatus();
        String proposedDecision = currentStatus.name();

        log.info("[ResolutionAgent] Status atual (pós-pipeline) | txId={} | status={}",
                transactionId, currentStatus);

        // ── [2] Model Armor: sanitiza o motivo antes de qualquer uso ──────────
        String sanitizedReason = modelArmorService.sanitizeAndValidate(triageReason);
        if (!sanitizedReason.equals(triageReason)) {
            log.warn("[ResolutionAgent] Reason sanitizado pelo ModelArmor | txId={}", transactionId);
        }

        // ── [3] Policy Gateway: aplica compliance corporativo ─────────────────
        TransactionStatus finalStatus = policyGateway.enforce(transaction, proposedDecision, riskScore);
        String policyJustification    = policyGateway.buildPolicyJustification(
                transaction, proposedDecision, finalStatus, riskScore);

        // ── [4] Aplica override se o Gateway sobrescreveu a decisão ───────────
        boolean wasOverridden = !finalStatus.equals(currentStatus);
        if (wasOverridden) {
            log.warn("[ResolutionAgent] ⚡ PolicyGateway sobrescreveu decisão | txId={} | {} → {}",
                    transactionId, currentStatus, finalStatus);
            ingestionService.updateStatus(transactionId, finalStatus);
        } else {
            log.info("[ResolutionAgent] ✓ PolicyGateway confirmou decisão | txId={} | status={}",
                    transactionId, finalStatus);
        }

        // ── [5] Persiste AuditLog final com tag RESOLUTION_AGENT_01 ──────────
        persistResolutionAuditLog(transactionId, sanitizedReason, riskScore,
                proposedDecision, finalStatus, policyJustification, wasOverridden);

        log.info("[ResolutionAgent] ✓ Resolução concluída | txId={} | finalStatus={} | overridden={}",
                transactionId, finalStatus, wasOverridden);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Persistência
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Persiste o AuditLog final com todos os metadados de compliance e identidade.
     *
     * <p>O campo {@code thoughtProcess} inclui:
     * <ul>
     *   <li>Identidade do agente (agentId, role, permissions) — Zero-Trust tag</li>
     *   <li>Decisão original do pipeline (pré-gateway)</li>
     *   <li>Decisão final aprovada pelo gateway</li>
     *   <li>Justificativa de política aplicada</li>
     *   <li>Motivo sanitizado pelo Model Armor</li>
     *   <li>Flag de override para auditoria de compliance</li>
     * </ul>
     *
     * <p>O conteúdo do thoughtProcess é inspecionado pelo {@link ModelArmorService#inspectOutput}
     * antes de ser persistido, prevenindo vazamento de PII no log de auditoria.
     */
    private void persistResolutionAuditLog(UUID transactionId,
                                            String sanitizedReason,
                                            double riskScore,
                                            String proposedDecision,
                                            TransactionStatus finalStatus,
                                            String policyJustification,
                                            boolean wasOverridden) {
        try {
            var node = objectMapper.createObjectNode();
            // Metadados de identidade Zero-Trust
            node.put("agentId",          IDENTITY.agentId());
            node.put("role",             IDENTITY.role());
            node.put("permissions",      IDENTITY.permissions().toString());
            // Decisão e compliance
            node.put("proposedDecision", proposedDecision);
            node.put("finalStatus",      finalStatus.name());
            node.put("policyOverridden", wasOverridden);
            node.put("policyJustification", policyJustification);
            node.put("riskScore",        riskScore);
            node.put("sanitizedReason",  sanitizedReason);
            node.put("resolvedAt",       LocalDateTime.now().toString());
            node.put("zeroTrustVerified", true);

            // Inspeciona o output contra PII antes de persistir
            String rawThoughtProcess  = objectMapper.writeValueAsString(node);
            String safeThoughtProcess = modelArmorService.inspectOutput(rawThoughtProcess);

            String decisionLabel = wasOverridden
                    ? "POLICY_OVERRIDE_" + proposedDecision + "_TO_" + finalStatus.name()
                    : "POLICY_CONFIRMED_" + finalStatus.name();

            AgentAuditLog auditLog = AgentAuditLog.builder()
                    .transactionId(transactionId)
                    .agentName(AGENT_NAME)
                    .thoughtProcess(safeThoughtProcess)
                    .decision(decisionLabel)
                    .timestamp(LocalDateTime.now())
                    .build();

            auditLogRepository.save(auditLog);

            log.info("[ResolutionAgent] AuditLog final gravado | txId={} | decision={} | agentId={}",
                    transactionId, decisionLabel, IDENTITY.agentId());

        } catch (JsonProcessingException e) {
            log.error("[ResolutionAgent] Falha ao serializar AuditLog final | txId={}", transactionId, e);
        }
    }
}
