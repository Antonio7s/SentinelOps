package com.sentinelops.api;

import com.sentinelops.api.dto.SystemMetricsDto;
import com.sentinelops.api.dto.TransactionTraceDto;
import com.sentinelops.core.ObservabilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Controller REST de Observabilidade — Etapa 6 do SentinelOps.
 *
 * <p>Expõe dois endpoints de leitura somente (auditoria e métricas) que permitem
 * que juízes técnicos, operadores e sistemas de monitoramento inspecionem o
 * pipeline de decisão de qualquer transação em tempo real.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /api/v1/observability/trace/{transactionId}} — traço E2E da transação</li>
 *   <li>{@code GET /api/v1/observability/metrics} — métricas globais do sistema</li>
 * </ul>
 *
 * <p>Todos os dados de conta são mascarados (Zero-Trust) antes de sair da
 * camada de serviço.
 */
@RestController
@RequestMapping("/api/v1/observability")
@RequiredArgsConstructor
@Slf4j
public class ObservabilityController {

    private final ObservabilityService observabilityService;

    // ─────────────────────────────────────────────────────────────────────────
    // Endpoints
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retorna o traço de execução completo de uma transação específica.
     *
     * <p>A resposta inclui cada agente que processou a transação, seu papel
     * Zero-Trust, o raciocínio completo da IA (thoughtProcess), a decisão
     * emitida, a latência de cada passo, e se o PolicyGateway interveio.
     *
     * @param transactionId UUID da transação a ser rastreada
     * @return 200 com {@link TransactionTraceDto} ou 404 se não encontrada
     */
    @GetMapping("/trace/{transactionId}")
    public ResponseEntity<TransactionTraceDto> getTransactionTrace(
            @PathVariable UUID transactionId) {

        log.info("[ObservabilityController] Solicitação de trace | transactionId={}", transactionId);

        return observabilityService.getTransactionTrace(transactionId)
                .map(trace -> {
                    log.info("[ObservabilityController] Trace encontrado | txId={} | steps={} | latency={}ms",
                            transactionId,
                            trace.executionChain().size(),
                            trace.totalLatencyMs());
                    return ResponseEntity.ok(trace);
                })
                .orElseGet(() -> {
                    log.warn("[ObservabilityController] Transação não encontrada | txId={}", transactionId);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * Retorna métricas globais do sistema SentinelOps em tempo real.
     *
     * <p>Inclui contadores de decisões por status, estado atual do Circuit Breaker
     * da API Gemini (Resilience4j) e a latência média de processamento do pipeline.
     *
     * @return 200 com {@link SystemMetricsDto}
     */
    @GetMapping("/metrics")
    public ResponseEntity<SystemMetricsDto> getSystemMetrics() {
        log.info("[ObservabilityController] Solicitação de métricas globais");
        SystemMetricsDto metrics = observabilityService.getSystemMetrics();
        log.info("[ObservabilityController] Métricas | total={} | approved={} | blocked={} | cb={}",
                metrics.totalTransactions(),
                metrics.approvedCount(),
                metrics.blockedCount(),
                metrics.circuitBreakerStatus());
        return ResponseEntity.ok(metrics);
    }
}
