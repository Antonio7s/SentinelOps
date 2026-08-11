package com.sentinelops.api.dto;

import java.time.LocalDateTime;

/**
 * Representa um passo de execução de um agente dentro do pipeline de triagem.
 *
 * <p>Cada instância captura <em>quem</em> processou, <em>o quê</em> decidiu,
 * e <em>quanto tempo</em> levou — fornecendo rastreabilidade completa para
 * auditores e juízes técnicos.
 *
 * @param stepOrder      posição na cadeia de execução (1 = TriageAgent, 2 = ForensicAgent, 3 = ResolutionAgent)
 * @param agentName      identificador canônico do agente (ex: "FORENSIC_AGENT_01")
 * @param identityRole   papel Zero-Trust do agente (ex: "DATA_ANALYST")
 * @param inputSummary   resumo humano-legível do input recebido pelo agente
 * @param thoughtProcess raciocínio completo da IA (JSON do AuditLog)
 * @param decision       decisão emitida por este agente neste passo
 * @param latencyMs      tempo de execução estimado em milissegundos
 * @param timestamp      data/hora de conclusão deste passo
 */
public record AgentExecutionStepDto(
        int            stepOrder,
        String         agentName,
        String         identityRole,
        String         inputSummary,
        String         thoughtProcess,
        String         decision,
        long           latencyMs,
        LocalDateTime  timestamp
) {}
