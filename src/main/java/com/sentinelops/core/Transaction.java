package com.sentinelops.core;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entidade JPA que representa uma transação financeira no SentinelOps.
 *
 * <p>Cada transação é o ponto de entrada do pipeline de análise por agentes de IA.
 * O fluxo completo é:
 * <ol>
 *   <li>Ingerida via {@code POST /api/v1/transactions} com status {@link TransactionStatus#PENDING}</li>
 *   <li>Analisada pelo {@code TriageAgent} (fase futura)</li>
 *   <li>Escalada ou finalizada com status {@code APPROVED}, {@code BLOCKED} ou {@code MANUAL_REVIEW}</li>
 * </ol>
 *
 * <h2>Mapeamento SQLite</h2>
 * O SQLite não possui tipo nativo UUID; o Hibernate armazena como {@code TEXT(36)}.
 * O enum {@link TransactionStatus} é persistido como {@code String} (nome),
 * evitando dependência da ordem de declaração.
 */
@Entity
@Table(name = "transactions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    // ─────────────────────────────────────────────────────────────────────────
    // Identificação
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Identificador único da transação.
     * Gerado pelo Hibernate via {@link UuidGenerator} (RFC-4122, sem separadores de banco).
     * Armazenado como TEXT no SQLite.
     */
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false, length = 36)
    private UUID id;

    // ─────────────────────────────────────────────────────────────────────────
    // Dados da Transação
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Identificador da conta originadora da transação.
     * Pode representar conta bancária, cartão ou identificador de usuário.
     */
    @Column(name = "account_id", nullable = false, length = 100)
    private String accountId;

    /**
     * Valor da transação em moeda local (precisão: 19 dígitos, 4 casas decimais).
     * Armazenado como TEXT/NUMERIC no SQLite para preservar precisão decimal.
     */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /**
     * Categoria do estabelecimento/merchant (ex: GROCERY, FUEL, ELECTRONICS).
     * Utilizado pelo TriageAgent como feature primária de análise de risco.
     */
    @Column(name = "merchant_category", nullable = false, length = 100)
    private String merchantCategory;

    /**
     * Timestamp exato em que a transação ocorreu (fuso horário do servidor).
     * Usado pelo ForensicAgent para detecção de padrões temporais (ex: transações noturnas).
     */
    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    // ─────────────────────────────────────────────────────────────────────────
    // Estado do Pipeline
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Estado atual da transação no pipeline de análise.
     *
     * <p>Persiste o <strong>nome</strong> do enum ({@code EnumType.STRING}) para garantir
     * que reordenações futuras do enum não corrompam dados históricos.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private TransactionStatus status = TransactionStatus.PENDING;
}
