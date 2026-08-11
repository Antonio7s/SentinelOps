package com.sentinelops.core;

import java.util.List;

/**
 * Identidade imutável de um agente no sistema SentinelOps — pilar Zero-Trust.
 *
 * <p>Cada agente opera com uma identidade declarada que define seu papel ({@code role})
 * e o conjunto de permissões ({@code permissions}) que ele pode exercer.
 * Nenhum agente deve acessar dados além do escopo definido em sua identidade.
 *
 * <h2>Princípio Zero-Trust aplicado</h2>
 * <ul>
 *   <li><b>Least Privilege</b>: cada agente recebe apenas as permissões mínimas necessárias.</li>
 *   <li><b>Explicit Verification</b>: toda operação sensível valida a identidade antes de executar.</li>
 *   <li><b>Data Minimization</b>: agentes com permissão {@code READ_ANONYMIZED_HISTORY} nunca
 *       recebem PII — os dados são anonimizados pelo {@link com.sentinelops.infrastructure.security.AnonymizationService}
 *       antes da entrega ao agente.</li>
 * </ul>
 *
 * <h2>Identidades Pré-definidas do Sistema</h2>
 * <pre>
 * AgentIdentity.TRIAGE_AGENT    — avalia risco inicial da transação
 * AgentIdentity.FORENSIC_AGENT  — investiga histórico comportamental (sem acesso a PII)
 * </pre>
 *
 * @param agentId     identificador único do agente (ex: "FORENSIC_AGENT_01")
 * @param role        papel funcional do agente (ex: "DATA_ANALYST")
 * @param permissions lista imutável de permissões concedidas
 */
public record AgentIdentity(
        String       agentId,
        String       role,
        List<String> permissions
) {

    // ─────────────────────────────────────────────────────────────────────────
    // Permissões disponíveis no sistema (catálogo de constantes)
    // ─────────────────────────────────────────────────────────────────────────

    /** Permissão para ler transações com status PENDING. */
    public static final String PERM_READ_TRANSACTIONS             = "READ_TRANSACTIONS";
    /** Permissão para atualizar o status de uma transação. */
    public static final String PERM_UPDATE_TRANSACTION_STATUS     = "UPDATE_TRANSACTION_STATUS";
    /** Permissão para ler histórico anonimizado (sem PII). */
    public static final String PERM_READ_ANONYMIZED_HISTORY       = "READ_ANONYMIZED_HISTORY";
    /** Permissão para gravar no AgentAuditLog. */
    public static final String PERM_WRITE_AUDIT_LOG               = "WRITE_AUDIT_LOG";

    // ─────────────────────────────────────────────────────────────────────────
    // Identidades pré-definidas (singletons do sistema)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Identidade do TriageAgent — analista de risco inicial.
     * Pode ler e atualizar transações, mas não acessa histórico anonimizado.
     */
    public static final AgentIdentity TRIAGE_AGENT = new AgentIdentity(
            "TRIAGE_AGENT_01",
            "RISK_ASSESSOR",
            List.of(
                    PERM_READ_TRANSACTIONS,
                    PERM_UPDATE_TRANSACTION_STATUS,
                    PERM_WRITE_AUDIT_LOG
            )
    );

    /**
     * Identidade do ForensicAgent — analista forense comportamental.
     * Opera EXCLUSIVAMENTE sobre dados anonimizados (Zero-Trust Data Minimization).
     * Não possui permissão para atualizar status de transações diretamente.
     */
    public static final AgentIdentity FORENSIC_AGENT = new AgentIdentity(
            "FORENSIC_AGENT_01",
            "DATA_ANALYST",
            List.of(
                    PERM_READ_ANONYMIZED_HISTORY,
                    PERM_WRITE_AUDIT_LOG
            )
    );

    /**
     * Identidade do ResolutionAgent — árbitro final de compliance.
     * É o único agente com permissão de {@code UPDATE_TRANSACTION_STATUS}
     * pós-pipeline (para sobrescritas do PolicyGateway). Não acessa histórico
     * anonimizado — escopo restrito à decisão final e auditoria de compliance.
     */
    public static final AgentIdentity RESOLUTION_AGENT = new AgentIdentity(
            "RESOLUTION_AGENT_01",
            "COMPLIANCE_OFFICER",
            List.of(
                    PERM_UPDATE_TRANSACTION_STATUS,
                    PERM_WRITE_AUDIT_LOG
            )
    );

    // ─────────────────────────────────────────────────────────────────────────
    // Métodos utilitários
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Compact constructor com validação defensiva.
     * Garante imutabilidade da lista de permissões.
     */
    public AgentIdentity {
        if (agentId == null || agentId.isBlank())
            throw new IllegalArgumentException("agentId não pode ser nulo ou vazio.");
        if (role == null || role.isBlank())
            throw new IllegalArgumentException("role não pode ser nulo ou vazio.");
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }

    /**
     * Verifica se este agente possui a permissão especificada.
     *
     * @param permission constante de permissão (use as constantes {@code PERM_*})
     * @return {@code true} se a permissão estiver concedida
     */
    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    /**
     * Lança {@link SecurityException} se a permissão não estiver concedida.
     * Padrão "fail-fast" para validação de acesso em pontos de entrada dos agentes.
     *
     * @param permission permissão requerida
     * @throws SecurityException se o agente não possuir a permissão
     */
    public void requirePermission(String permission) {
        if (!hasPermission(permission)) {
            throw new SecurityException(String.format(
                    "[ZeroTrust] Agente '%s' (role=%s) não possui permissão '%s'. Acesso negado.",
                    agentId, role, permission));
        }
    }

    @Override
    public String toString() {
        return String.format("AgentIdentity{agentId='%s', role='%s', permissions=%s}",
                agentId, role, permissions);
    }
}
