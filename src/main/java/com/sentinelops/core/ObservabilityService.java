package com.sentinelops.core;

import com.sentinelops.api.dto.AgentExecutionStepDto;
import com.sentinelops.api.dto.SystemMetricsDto;
import com.sentinelops.api.dto.TransactionTraceDto;
import com.sentinelops.infrastructure.security.AnonymizationService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Serviço de Observabilidade do SentinelOps.
 *
 * <h2>Responsabilidades</h2>
 * <ol>
 *   <li>Reconstruir o traço de execução end-to-end de uma transação específica,
 *       incluindo cada passo de agente com latência e raciocínio da IA.</li>
 *   <li>Agregar métricas do sistema: volume por status, Circuit Breaker e
 *       latência média de processamento.</li>
 * </ol>
 *
 * <p>Todos os dados expostos seguem o princípio Zero-Trust: identificadores de
 * conta são mascarados via {@link AnonymizationService} antes de qualquer retorno.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ObservabilityService {

    private static final String CB_NAME = "geminiApiCircuitBreaker";

    // ─── Constantes de nome de agente (espelham as constantes dos próprios agentes) ─
    private static final String AGENT_TRIAGE     = "TRIAGE";
    private static final String AGENT_FORENSIC   = "FORENSIC";
    private static final String AGENT_RESOLUTION = "RESOLUTION";

    private final TransactionRepository    transactionRepository;
    private final AgentAuditLogRepository  auditLogRepository;
    private final AnonymizationService     anonymizationService;
    private final CircuitBreakerRegistry   circuitBreakerRegistry;

    // ─────────────────────────────────────────────────────────────────────────
    // API Pública
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reconstrói o traço cronológico completo de uma transação.
     *
     * <p>Cada {@link AgentAuditLog} do banco é convertido em um
     * {@link AgentExecutionStepDto} com seu papel Zero-Trust, latência
     * estimada e raciocínio da IA. O campo {@code policyOverridden} é
     * derivado inspecionando-se a decisão do ResolutionAgent.
     *
     * @param transactionId UUID da transação a rastrear
     * @return Optional vazio se a transação não for encontrada
     */
    @Transactional(readOnly = true)
    public Optional<TransactionTraceDto> getTransactionTrace(UUID transactionId) {
        return transactionRepository.findById(transactionId).map(transaction -> {

            List<AgentAuditLog> logs =
                    auditLogRepository.findByTransactionIdOrderByTimestampAsc(transactionId);

            // ── Calcula latência total: ingestão → último AuditLog ────────────
            long totalLatencyMs = 0L;
            if (!logs.isEmpty()) {
                LocalDateTime start = transaction.getTimestamp();
                LocalDateTime end   = logs.get(logs.size() - 1).getTimestamp();
                totalLatencyMs = Duration.between(start, end).toMillis();
                if (totalLatencyMs < 0) totalLatencyMs = 0L;
            }

            // ── Detecta override do PolicyGateway ─────────────────────────────
            boolean policyOverridden = false;
            String  overrideReason   = null;
            for (AgentAuditLog log : logs) {
                if (log.getAgentName() != null
                        && log.getAgentName().toUpperCase().contains(AGENT_RESOLUTION)
                        && log.getDecision() != null
                        && log.getDecision().toUpperCase().startsWith("POLICY_OVERRIDE")) {
                    policyOverridden = true;
                    overrideReason   = log.getDecision();
                    break;
                }
            }

            // ── Monta a execution chain ────────────────────────────────────────
            List<AgentExecutionStepDto> chain = buildExecutionChain(transaction, logs);

            return new TransactionTraceDto(
                    transactionId,
                    anonymizationService.maskAccountId(transaction.getAccountId()),
                    transaction.getAmount(),
                    transaction.getMerchantCategory(),
                    transaction.getStatus(),
                    totalLatencyMs,
                    policyOverridden,
                    overrideReason,
                    chain
            );
        });
    }

    /**
     * Agrega métricas globais do sistema em tempo real.
     *
     * <p>Inclui contadores de status, estado do Circuit Breaker da API Gemini
     * e a latência média de processamento calculada sobre transações já concluídas.
     *
     * @return snapshot atual das métricas do sistema
     */
    @Transactional(readOnly = true)
    public SystemMetricsDto getSystemMetrics() {
        long approved     = transactionRepository.countByStatus(TransactionStatus.APPROVED);
        long blocked      = transactionRepository.countByStatus(TransactionStatus.BLOCKED);
        long manualReview = transactionRepository.countByStatus(TransactionStatus.MANUAL_REVIEW);
        long pending      = transactionRepository.countByStatus(TransactionStatus.PENDING);
        long total        = approved + blocked + manualReview + pending;
        long decided      = approved + blocked + manualReview;

        // ── Aprovação rate sobre o universo decidido ──────────────────────────
        double approvalRate = decided == 0 ? 0.0 : (double) approved / decided;

        // ── Estado do Circuit Breaker (Resilience4j) ──────────────────────────
        String cbStatus = resolveCircuitBreakerStatus();

        // ── Latência média: média das durações de cada transação decidida ─────
        double avgLatency = computeAverageLatencyMs();

        log.debug("[ObservabilityService] Métricas calculadas | total={} | approved={} | blocked={} | " +
                  "manualReview={} | pending={} | cb={} | avgLatency={}ms",
                  total, approved, blocked, manualReview, pending, cbStatus, avgLatency);

        return new SystemMetricsDto(total, approved, blocked, manualReview, pending,
                                   cbStatus, avgLatency, approvalRate);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers privados
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Converte a lista de {@link AgentAuditLog}s em uma cadeia ordenada de
     * {@link AgentExecutionStepDto}s com latência calculada entre passos.
     */
    private List<AgentExecutionStepDto> buildExecutionChain(
            Transaction transaction, List<AgentAuditLog> logs) {

        List<AgentExecutionStepDto> chain = new ArrayList<>();
        LocalDateTime previousTimestamp   = transaction.getTimestamp();

        for (int i = 0; i < logs.size(); i++) {
            AgentAuditLog log = logs.get(i);

            long stepLatencyMs = 0L;
            if (log.getTimestamp() != null && previousTimestamp != null) {
                stepLatencyMs = Duration.between(previousTimestamp, log.getTimestamp()).toMillis();
                if (stepLatencyMs < 0) stepLatencyMs = 0L;
            }

            chain.add(new AgentExecutionStepDto(
                    i + 1,
                    log.getAgentName(),
                    resolveIdentityRole(log.getAgentName()),
                    buildInputSummary(log.getAgentName(), transaction),
                    log.getThoughtProcess(),
                    log.getDecision(),
                    stepLatencyMs,
                    log.getTimestamp()
            ));

            previousTimestamp = log.getTimestamp();
        }
        return chain;
    }

    /**
     * Mapeia o nome do agente para o papel Zero-Trust correspondente
     * (espelha os singletons de {@link com.sentinelops.core.AgentIdentity}).
     */
    private String resolveIdentityRole(String agentName) {
        if (agentName == null) return "UNKNOWN";
        String upper = agentName.toUpperCase();
        if (upper.contains(AGENT_TRIAGE))     return "RISK_ANALYST";
        if (upper.contains(AGENT_FORENSIC))   return "DATA_ANALYST";
        if (upper.contains(AGENT_RESOLUTION)) return "COMPLIANCE_OFFICER";
        return "UNKNOWN";
    }

    /**
     * Gera um resumo legível do input recebido pelo agente para o juiz técnico.
     */
    private String buildInputSummary(String agentName, Transaction transaction) {
        if (agentName == null) return "N/A";
        String upper = agentName.toUpperCase();
        if (upper.contains(AGENT_TRIAGE))
            return String.format("Transação de R$%s na categoria %s — conta %s",
                    transaction.getAmount().toPlainString(),
                    transaction.getMerchantCategory(),
                    anonymizationService.maskAccountId(transaction.getAccountId()));
        if (upper.contains(AGENT_FORENSIC))
            return String.format("Histórico comportamental anonimizado da conta %s",
                    anonymizationService.maskAccountId(transaction.getAccountId()));
        if (upper.contains(AGENT_RESOLUTION))
            return "Decisão pós-pipeline submetida ao PolicyGateway para arbitragem final";
        return "Contexto da transação " + transaction.getId();
    }

    /**
     * Consulta o {@link CircuitBreakerRegistry} do Resilience4j para obter
     * o estado atual do circuit breaker da API Gemini.
     * Retorna "UNKNOWN" se o circuit breaker não estiver registrado.
     */
    private String resolveCircuitBreakerStatus() {
        try {
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(CB_NAME);
            return cb.getState().name();
        } catch (Exception e) {
            log.debug("[ObservabilityService] Circuit breaker '{}' não registrado: {}", CB_NAME, e.getMessage());
            return "UNKNOWN";
        }
    }

    /**
     * Calcula a latência média de processamento para transações já concluídas.
     * Para cada transação decidida, mede a duração entre sua ingestão e o
     * timestamp do último AuditLog registrado.
     */
    private double computeAverageLatencyMs() {
        try {
            List<Transaction> decided = transactionRepository.findByStatusNot(TransactionStatus.PENDING);
            if (decided.isEmpty()) return 0.0;

            return decided.stream()
                    .mapToLong(tx -> {
                        List<AgentAuditLog> txLogs =
                                auditLogRepository.findByTransactionIdOrderByTimestampAsc(tx.getId());
                        if (txLogs.isEmpty()) return 0L;
                        long ms = Duration.between(
                                tx.getTimestamp(),
                                txLogs.get(txLogs.size() - 1).getTimestamp()
                        ).toMillis();
                        return Math.max(0L, ms);
                    })
                    .average()
                    .orElse(0.0);
        } catch (Exception e) {
            log.warn("[ObservabilityService] Falha ao calcular latência média: {}", e.getMessage());
            return 0.0;
        }
    }
}
