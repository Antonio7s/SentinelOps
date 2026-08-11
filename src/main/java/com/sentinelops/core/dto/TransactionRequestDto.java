package com.sentinelops.core.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO de entrada para ingestão de uma nova transação financeira.
 *
 * <p>Recebido pelo endpoint {@code POST /api/v1/transactions}.
 * Validado via Bean Validation antes de ser convertido para a entidade {@link com.sentinelops.core.Transaction}.
 *
 * <h2>Exemplo de Payload JSON</h2>
 * <pre>
 * {
 *   "accountId":        "ACC-00123456",
 *   "amount":           1850.75,
 *   "merchantCategory": "ELECTRONICS",
 *   "timestamp":        "2026-08-10T01:15:00"
 * }
 * </pre>
 *
 * <p>Se {@code timestamp} não for fornecido, o serviço utilizará {@link LocalDateTime#now()}.
 */
public record TransactionRequestDto(

        /**
         * Identificador da conta originadora.
         * Obrigatório, não-vazio, máximo de 100 caracteres.
         */
        @NotBlank(message = "accountId é obrigatório e não pode ser vazio.")
        @Size(max = 100, message = "accountId deve ter no máximo 100 caracteres.")
        String accountId,

        /**
         * Valor monetário da transação. Deve ser estritamente positivo.
         */
        @NotNull(message = "amount é obrigatório.")
        @DecimalMin(value = "0.01", message = "amount deve ser maior que zero.")
        BigDecimal amount,

        /**
         * Categoria do estabelecimento comercial.
         * Exemplos: GROCERY, FUEL, ELECTRONICS, TRAVEL, ENTERTAINMENT.
         */
        @NotBlank(message = "merchantCategory é obrigatória.")
        @Size(max = 100, message = "merchantCategory deve ter no máximo 100 caracteres.")
        String merchantCategory,

        /**
         * Timestamp da transação em ISO-8601.
         * Opcional — se omitido, o serviço usa {@link LocalDateTime#now()}.
         */
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime timestamp

) {
    /**
     * Compact constructor: se timestamp for nulo, substitui pelo instante atual.
     * Isso elimina a necessidade de null-check em toda a camada de serviço.
     */
    public TransactionRequestDto {
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
    }
}
