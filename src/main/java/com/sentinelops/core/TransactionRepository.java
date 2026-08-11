package com.sentinelops.core;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para a entidade {@link Transaction}.
 *
 * <p>Provê operações CRUD automáticas via {@link JpaRepository} e queries
 * derivadas/JPQL para os casos de uso centrais do SentinelOps.
 *
 * <p>A camada de serviço nunca deve expor entidades diretamente ao controller;
 * use DTOs de saída para todas as respostas HTTP.
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * Busca todas as transações de uma conta específica, ordenadas da mais recente para a mais antiga.
     *
     * @param accountId identificador da conta
     * @return lista de transações (pode ser vazia)
     */
    List<Transaction> findByAccountIdOrderByTimestampDesc(String accountId);

    /**
     * Busca todas as transações com um status específico.
     * Útil para dashboards de operações (ex: listar todas as PENDING ou MANUAL_REVIEW).
     *
     * @param status status de filtro
     * @return lista de transações naquele estado
     */
    List<Transaction> findByStatusOrderByTimestampDesc(TransactionStatus status);

    /**
     * Conta quantas transações de uma conta estão em um dado status.
     * Útil para detectar padrões de abuso (ex: muitos PENDING de um mesmo accountId).
     *
     * @param accountId identificador da conta
     * @param status    status de filtro
     * @return contagem de transações
     */
    long countByAccountIdAndStatus(String accountId, TransactionStatus status);

    /**
     * Verifica se existe pelo menos uma transação PENDING para a conta.
     * Usado pelo TriageAgent para priorizar filas (fase futura).
     *
     * @param accountId identificador da conta
     * @return {@code true} se houver transação PENDING
     */
    boolean existsByAccountIdAndStatus(String accountId, TransactionStatus status);

    /**
     * Query JPQL customizada: busca as N transações mais recentes de uma conta
     * em qualquer status. Útil para janela de contexto do ForensicAgent.
     *
     * @param accountId identificador da conta
     * @param limit     número máximo de resultados
     * @return lista de transações (até {@code limit} elementos)
     */
    @Query("SELECT t FROM Transaction t WHERE t.accountId = :accountId ORDER BY t.timestamp DESC LIMIT :limit")
    List<Transaction> findRecentByAccountId(@Param("accountId") String accountId,
                                             @Param("limit") int limit);

    /**
     * Busca uma transação pelo ID, retornando Optional para tratamento seguro de nulo.
     *
     * <p>Sobrescrita explícita do método herdado para fins de documentação;
     * o comportamento é idêntico ao {@code findById} do JpaRepository.
     *
     * @param id UUID da transação
     * @return Optional contendo a transação ou vazio
     */
    Optional<Transaction> findById(UUID id);

    /**
     * Conta todas as transações em um determinado status.
     * Usado pelo {@code ObservabilityService} para métricas agregadas do sistema.
     *
     * @param status status de filtro
     * @return total de transações naquele estado
     */
    long countByStatus(TransactionStatus status);

    /**
     * Retorna todas as transações que NÃO estão em um dado status.
     * Usado para computar a latência média de processamento (excluindo PENDING).
     *
     * @param status status a excluir (tipicamente PENDING)
     * @return lista de transações já decididas
     */
    List<Transaction> findByStatusNot(TransactionStatus status);
}
