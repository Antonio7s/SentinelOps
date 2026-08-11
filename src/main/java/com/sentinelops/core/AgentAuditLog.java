package com.sentinelops.core;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entidade JPA que registra o "rastro de auditoria" das decisões dos agentes de IA.
 *
 * <p>Cada entrada representa um "pensamento" registrado por um agente específico
 * durante a análise de uma {@link Transaction}. Este log é a fonte primária de
 * explicabilidade (XAI — Explainable AI) do sistema SentinelOps.
 *
 * <h2>Uso Típico</h2>
 * <pre>
 * AgentAuditLog.builder()
 *     .transactionId(txId)
 *     .agentName("TriageAgent")
 *     .thoughtProcess(geminiRawResponse)   // pode ser centenas de tokens
 *     .decision("ESCALATE_TO_FORENSIC")
 *     .timestamp(LocalDateTime.now())
 *     .build();
 * </pre>
 *
 * <h2>Mapeamento SQLite</h2>
 * O campo {@code thoughtProcess} usa {@code columnDefinition = "TEXT"} para
 * garantir que o SQLite aloque espaço variável ilimitado, essencial para
 * armazenar respostas brutas do Gemini (até ~8192 tokens por chamada).
 */
@Entity
@Table(
    name = "agent_audit_logs",
    indexes = {
        // Índice composto para queries de auditoria por transação + agente
        @Index(name = "idx_audit_transaction_agent", columnList = "transaction_id, agent_name"),
        // Índice temporal para relatórios históricos por período
        @Index(name = "idx_audit_timestamp",         columnList = "timestamp")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentAuditLog {

    // ─────────────────────────────────────────────────────────────────────────
    // Identificação
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Identificador único deste registro de auditoria.
     */
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false, length = 36)
    private UUID id;

    // ─────────────────────────────────────────────────────────────────────────
    // Vínculo com a Transação
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * UUID da {@link Transaction} que gerou esta entrada de auditoria.
     *
     * <p>Não usamos {@code @ManyToOne} intencionalmente: logs de auditoria são
     * imutáveis e não devem carregar o grafo JPA da transação em memória.
     * A referência por ID é suficiente para queries de auditoria.
     */
    @Column(name = "transaction_id", nullable = false, updatable = false, length = 36)
    private UUID transactionId;

    // ─────────────────────────────────────────────────────────────────────────
    // Dados do Agente
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Nome canônico do agente que gerou este registro.
     * Exemplos: {@code "TriageAgent"}, {@code "ForensicAgent"}, {@code "SummaryAgent"}.
     */
    @Column(name = "agent_name", nullable = false, length = 100)
    private String agentName;

    /**
     * Cadeia de raciocínio completa do agente — geralmente o JSON ou texto bruto
     * retornado pelo Gemini.
     *
     * <p>{@code columnDefinition = "TEXT"} força o SQLite a usar o tipo TEXT
     * de comprimento ilimitado, adequado para respostas longas do LLM.
     */
    @Column(name = "thought_process", nullable = false, columnDefinition = "TEXT")
    private String thoughtProcess;

    /**
     * Decisão final tomada pelo agente após o processamento.
     * Exemplos: {@code "APPROVE"}, {@code "ESCALATE_TO_FORENSIC"}, {@code "BLOCK_TRANSACTION"}.
     */
    @Column(name = "decision", nullable = false, length = 100)
    private String decision;

    /**
     * Timestamp exato em que o agente registrou esta entrada.
     * Permite reconstituir a sequência temporal de decisões sobre uma transação.
     */
    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;
}
