package com.sentinelops.api.dto;

/**
 * Métricas globais do sistema SentinelOps.
 *
 * <p>Exposto via {@code GET /api/v1/observability/metrics}, permite que juízes técnicos
 * e operadores visualizem o volume de transações por decisão, a saúde do Circuit Breaker
 * e a latência média de processamento em tempo real.
 *
 * @param totalTransactions       total de transações no sistema (todas as decisões)
 * @param approvedCount           transações aprovadas automaticamente pelo pipeline
 * @param blockedCount            transações bloqueadas (fraude detectada)
 * @param manualReviewCount       transações escaladas para revisão humana
 * @param pendingCount            transações ainda aguardando triagem
 * @param circuitBreakerStatus    estado atual do Circuit Breaker da API Gemini
 *                                (CLOSED = normal, OPEN = API indisponível, HALF_OPEN = recuperando)
 * @param averageProcessingTimeMs latência média do pipeline completo (em milissegundos)
 * @param approvalRate            percentual de aprovações sobre o total decidido (0.0–1.0)
 */
public record SystemMetricsDto(
        long   totalTransactions,
        long   approvedCount,
        long   blockedCount,
        long   manualReviewCount,
        long   pendingCount,
        String circuitBreakerStatus,
        double averageProcessingTimeMs,
        double approvalRate
) {}
