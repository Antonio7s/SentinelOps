package com.sentinelops.core.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.sentinelops.core.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO de saída retornado pelo endpoint {@code POST /api/v1/transactions}.
 *
 * <p>Contém apenas os campos relevantes para o cliente confirmar a ingestão:
 * o UUID gerado, o status inicial e um link simbólico para polling de status futuro.
 *
 * <h2>Exemplo de Resposta JSON (HTTP 202 Accepted)</h2>
 * <pre>
 * {
 *   "transactionId":    "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
 *   "accountId":        "ACC-00123456",
 *   "amount":           1850.75,
 *   "merchantCategory": "ELECTRONICS",
 *   "status":           "PENDING",
 *   "ingestedAt":       "2026-08-10T01:15:00",
 *   "statusUrl":        "/api/v1/transactions/a1b2c3d4-e5f6-7890-abcd-ef1234567890"
 * }
 * </pre>
 */
public record TransactionResponseDto(

        /**
         * UUID gerado e atribuído à transação pelo sistema.
         * O cliente deve usar este ID para consultas de status futuras.
         */
        UUID transactionId,

        /**
         * Conta originadora (echo do request para confirmação).
         */
        String accountId,

        /**
         * Valor da transação (echo do request para confirmação).
         */
        BigDecimal amount,

        /**
         * Categoria do merchant (echo do request).
         */
        String merchantCategory,

        /**
         * Status inicial da transação — sempre {@link TransactionStatus#PENDING}
         * imediatamente após a ingestão.
         */
        TransactionStatus status,

        /**
         * Timestamp de ingestão no sistema (pode diferir do timestamp da transação original).
         */
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime ingestedAt,

        /**
         * URL relativa para consulta do status atual desta transação.
         * Permite que o cliente implemente polling sem conhecer a URL de antemão.
         */
        String statusUrl

) {
    /**
     * Factory method de conveniência para construir a resposta a partir da entidade salva.
     *
     * @param transaction entidade persistida com UUID já gerado
     * @return DTO de resposta pronto para serialização
     */
    public static TransactionResponseDto from(com.sentinelops.core.Transaction transaction) {
        return new TransactionResponseDto(
                transaction.getId(),
                transaction.getAccountId(),
                transaction.getAmount(),
                transaction.getMerchantCategory(),
                transaction.getStatus(),
                transaction.getTimestamp(),
                "/api/v1/transactions/" + transaction.getId()
        );
    }
}
