/**
 * Pacote {@code com.sentinelops.agents} — Agentes de IA do SentinelOps.
 *
 * <p>Cada agente é um componente Spring especializado que orquestra:
 * <ul>
 *   <li>Chamadas ao {@code GeminiApiClient} da camada de infraestrutura</li>
 *   <li>Leitura/escrita de dados via repositórios do pacote {@code core}</li>
 *   <li>Lógica de decisão específica do domínio de segurança/operações</li>
 * </ul>
 *
 * <p>Exemplos futuros: {@code ThreatAnalysisAgent}, {@code AnomalyDetectionAgent}.
 */
package com.sentinelops.agents;
