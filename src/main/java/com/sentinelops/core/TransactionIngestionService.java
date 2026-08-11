package com.sentinelops.core;

import com.sentinelops.core.dto.TransactionRequestDto;
import com.sentinelops.core.dto.TransactionResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Serviço de ingestão de transações financeiras no SentinelOps.
 *
 * <p>Responsabilidades:
 * <ol>
 *   <li>Converter o DTO de entrada em entidade {@link Transaction}.</li>
 *   <li>Persistir com status {@link TransactionStatus#PENDING} (ponto de entrada do pipeline).</li>
 *   <li>Emitir log de sistema no padrão de auditoria.</li>
 *   <li>Retornar o DTO de resposta com o UUID gerado.</li>
 * </ol>
 *
 * <h2>Transacionalidade</h2>
 * O método {@link #ingest} é anotado com {@code @Transactional} para garantir que
 * o save e qualquer operação futura (ex: publicar evento) sejam atômicos.
 * Em caso de exceção, o rollback previne transações órfãs no banco.
 *
 * <h2>Extensão Futura</h2>
 * <p>Após persistir, este serviço deverá publicar um evento de domínio
 * (ex: {@code TransactionIngestedEvent}) para que o {@code TriageAgent}
 * seja ativado de forma desacoplada via Spring Events ou uma fila de mensagens.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionIngestionService {

    private final TransactionRepository transactionRepository;

    // ─────────────────────────────────────────────────────────────────────────
    // API Pública
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ingere uma nova transação financeira no sistema SentinelOps.
     *
     * <p>O status inicial é sempre {@link TransactionStatus#PENDING}, independentemente
     * do payload recebido — a camada de API não permite que clientes externos
     * definam o status inicial de uma transação.
     *
     * @param dto dados da transação recebidos via HTTP
     * @return DTO de resposta contendo o UUID gerado e o status PENDING
     * @throws org.springframework.dao.DataAccessException em falha de persistência
     */
    @Transactional
    public TransactionResponseDto ingest(TransactionRequestDto dto) {
        log.debug("[IngestionService] Convertendo DTO para entidade | accountId={} | amount={} | category={}",
                dto.accountId(), dto.amount(), dto.merchantCategory());

        // ── Constrói a entidade a partir do DTO ───────────────────────────
        Transaction transaction = Transaction.builder()
                .accountId(dto.accountId())
                .amount(dto.amount())
                .merchantCategory(dto.merchantCategory())
                .timestamp(dto.timestamp())
                .status(TransactionStatus.PENDING)
                .build();

        // ── Persiste no SQLite (UUID gerado pelo @UuidGenerator do Hibernate) ─
        Transaction saved = transactionRepository.save(transaction);

        // ── Log de sistema no padrão canônico do SentinelOps ─────────────
        log.info("Transaction [{}] ingested. Awaiting Agent Triage. | accountId={} | amount={} | category={} | ingestedAt={}",
                saved.getId(),
                saved.getAccountId(),
                saved.getAmount(),
                saved.getMerchantCategory(),
                LocalDateTime.now());

        return TransactionResponseDto.from(saved);
    }

    /**
     * Atualiza o status de uma transação existente.
     *
     * <p>Usado pelos agentes para avançar o estado do pipeline.
     * O agente deve registrar a mudança de status em {@link AgentAuditLog}
     * antes de chamar este método para garantir rastreabilidade.
     *
     * @param transactionId UUID da transação a atualizar
     * @param newStatus     novo status a ser aplicado
     * @throws jakarta.persistence.EntityNotFoundException se a transação não existir
     */
    @Transactional
    public void updateStatus(java.util.UUID transactionId, TransactionStatus newStatus) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> {
                    log.error("[IngestionService] Transação não encontrada para atualização | id={}", transactionId);
                    return new jakarta.persistence.EntityNotFoundException(
                            "Transaction not found: " + transactionId);
                });

        TransactionStatus previousStatus = transaction.getStatus();
        transaction.setStatus(newStatus);
        transactionRepository.save(transaction);

        log.info("[IngestionService] Transaction [{}] status updated: {} → {}",
                transactionId, previousStatus, newStatus);
    }
}
