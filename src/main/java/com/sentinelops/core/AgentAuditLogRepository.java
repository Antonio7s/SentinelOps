package com.sentinelops.core;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para a entidade {@link AgentAuditLog}.
 *
 * <p>O audit log é essencialmente append-only: nunca é atualizado ou deletado
 * em operação normal. Todos os métodos de escrita herdados do {@link JpaRepository}
 * devem ser usados com cautela pela camada de serviço.
 */
@Repository
public interface AgentAuditLogRepository extends JpaRepository<AgentAuditLog, UUID> {

    /**
     * Recupera todo o histórico de auditoria de uma transação específica,
     * ordenado cronologicamente. Essencial para reconstruir o pipeline de decisão.
     *
     * @param transactionId UUID da transação analisada
     * @return lista de logs na ordem em que foram gerados
     */
    List<AgentAuditLog> findByTransactionIdOrderByTimestampAsc(UUID transactionId);

    /**
     * Recupera apenas os logs gerados por um agente específico para uma transação.
     * Útil quando o ForensicAgent precisa ler apenas o raciocínio do TriageAgent.
     *
     * @param transactionId UUID da transação
     * @param agentName     nome canônico do agente
     * @return lista de logs daquele agente para aquela transação
     */
    List<AgentAuditLog> findByTransactionIdAndAgentNameOrderByTimestampAsc(
            UUID transactionId, String agentName);

    /**
     * Busca todos os logs de um agente em um intervalo de tempo.
     * Útil para monitoramento de performance e geração de relatórios diários.
     *
     * @param agentName     nome canônico do agente
     * @param from          início do intervalo (inclusive)
     * @param to            fim do intervalo (inclusive)
     * @return lista de logs no período
     */
    List<AgentAuditLog> findByAgentNameAndTimestampBetween(
            String agentName, LocalDateTime from, LocalDateTime to);

    /**
     * Conta quantas decisões cada agente tomou em um dado período.
     * Usado para métricas de throughput do sistema.
     *
     * @param from início do intervalo
     * @param to   fim do intervalo
     * @return lista de pares [agentName, count]
     */
    @Query("SELECT a.agentName, COUNT(a) FROM AgentAuditLog a " +
           "WHERE a.timestamp BETWEEN :from AND :to GROUP BY a.agentName")
    List<Object[]> countDecisionsByAgentInPeriod(@Param("from") LocalDateTime from,
                                                  @Param("to")   LocalDateTime to);

    /**
     * Verifica se já existe pelo menos um log de um agente para a transação.
     * Evita que o mesmo agente processe a mesma transação duas vezes.
     *
     * @param transactionId UUID da transação
     * @param agentName     nome do agente
     * @return {@code true} se o agente já processou esta transação
     */
    boolean existsByTransactionIdAndAgentName(UUID transactionId, String agentName);
}
