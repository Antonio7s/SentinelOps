package com.sentinelops.api.dto;

import com.sentinelops.core.TransactionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Traço completo de execução de uma transação pelo pipeline de agentes.
 *
 * <p>Exposto via {@code GET /api/v1/observability/trace/{transactionId}},
 * este DTO fornece visibilidade total sobre cada decisão tomada pelos agentes,
 * incluindo se houve intervenção do PolicyGateway (Human-in-the-Loop).
 *
 * @param transactionId     UUID da transação rastreada
 * @param accountIdMasked   ID da conta com PII mascarado (Zero-Trust)
 * @param amount            valor da transação
 * @param merchantCategory  categoria do estabelecimento
 * @param finalStatus       status final após arbitragem do ResolutionAgent
 * @param totalLatencyMs    latência total do pipeline (ingestão → resolução final)
 * @param policyOverridden  {@code true} se o PolicyGateway modificou a decisão inicial
 * @param overrideReason    justificativa da sobrescrita (nulo se não houve override)
 * @param executionChain    cadeia cronológica de passos executados pelos agentes
 */
public record TransactionTraceDto(
        UUID                        transactionId,
        String                      accountIdMasked,
        BigDecimal                  amount,
        String                      merchantCategory,
        TransactionStatus           finalStatus,
        long                        totalLatencyMs,
        boolean                     policyOverridden,
        String                      overrideReason,
        List<AgentExecutionStepDto> executionChain
) {}
