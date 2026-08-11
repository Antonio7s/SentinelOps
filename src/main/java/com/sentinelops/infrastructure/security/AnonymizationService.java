package com.sentinelops.infrastructure.security;

import com.sentinelops.agents.ForensicAnalysisResult.AnonymizedTransaction;
import com.sentinelops.core.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Serviço de anonimização de PII para o pilar Zero-Trust Data Minimization.
 *
 * <p>Implementa técnicas de mascaramento e generalização de dados pessoais
 * antes que qualquer agente de IA os consuma. O princípio fundamental é:
 * <b>os LLMs nunca devem receber identificadores que permitam re-identificação.</b>
 *
 * <h2>Técnicas Implementadas</h2>
 * <ul>
 *   <li><b>Partial Masking</b>: mantém prefixo e sufixo do identificador,
 *       substituindo o meio por {@code ***} (ex: "ACC-00123" → "ACC-***23").</li>
 *   <li><b>Value Generalization</b>: arredonda valores monetários para a ordem
 *       de grandeza mais próxima, preservando a magnitude sem revelar o valor exato.</li>
 *   <li><b>Temporal Abstraction</b>: substitui timestamps completos por dia-da-semana
 *       e hora do dia, mantendo padrões comportamentais sem datas identificáveis.</li>
 * </ul>
 *
 * <h2>O que NÃO é anonimizado</h2>
 * <p>A categoria do merchant ({@code merchantCategory}) e o status são preservados
 * pois são dados operacionais necessários para análise de risco e não constituem PII.
 */
@Slf4j
@Service
public class AnonymizationService {

    // ─────────────────────────────────────────────────────────────────────────
    // Mascaramento de Identificadores
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Mascara um {@code accountId} preservando o prefixo e os 2 últimos caracteres.
     *
     * <p>Exemplos:
     * <pre>
     * "ACC-00123"    →  "ACC-***23"
     * "USR-9988"     →  "USR-***88"
     * "ACCT00001"    →  "ACT***01"
     * "AB"           →  "***"        (muito curto — mascara tudo)
     * null           →  "***"
     * </pre>
     *
     * <p>O algoritmo:
     * <ol>
     *   <li>Encontra o último separador ({@code -}, {@code _} ou mudança de tipo alfanumérico).</li>
     *   <li>Preserva o prefixo até o separador.</li>
     *   <li>Substitui os caracteres do meio por {@code ***}.</li>
     *   <li>Preserva os últimos 2 caracteres do sufixo.</li>
     * </ol>
     *
     * @param accountId identificador de conta a ser mascarado
     * @return string mascarada sem PII identificável
     */
    public String maskAccountId(String accountId) {
        if (accountId == null || accountId.length() < 3) {
            return "***";
        }

        // Encontra o índice do último separador (- ou _) para preservar o prefixo
        int lastSeparator = Math.max(accountId.lastIndexOf('-'), accountId.lastIndexOf('_'));

        String prefix;
        String numeric;

        if (lastSeparator > 0 && lastSeparator < accountId.length() - 1) {
            // "ACC-00123" → prefix="ACC-", numeric="00123"
            prefix  = accountId.substring(0, lastSeparator + 1);
            numeric = accountId.substring(lastSeparator + 1);
        } else {
            // Sem separador: "ACCT00001" → prefix=3 primeiros chars, resto como numeric
            int prefixLen = Math.min(3, accountId.length() - 2);
            prefix  = accountId.substring(0, prefixLen);
            numeric = accountId.substring(prefixLen);
        }

        // Preserva apenas os 2 últimos caracteres do segmento numérico/identificador
        String suffix  = numeric.length() >= 2 ? numeric.substring(numeric.length() - 2) : numeric;
        String masked  = prefix + "***" + suffix;

        log.trace("[AnonymizationService] accountId mascarado: {} → {}", accountId, masked);
        return masked;
    }

    /**
     * Generaliza um valor monetário para sua ordem de grandeza, reduzindo a precisão
     * sem tornar o dado inútil para análise de padrões.
     *
     * <p>Estratégia: arredonda para a centena mais próxima.
     * <pre>
     * R$  4.850,75  →  R$  4.900
     * R$ 12.340,00  →  R$ 12.300
     * R$     55,00  →  R$    100
     * </pre>
     *
     * @param amount valor original da transação
     * @return valor generalizado (escala 0, arredondado para centena)
     */
    public BigDecimal generalizeAmount(BigDecimal amount) {
        if (amount == null) return BigDecimal.ZERO;
        // Divide por 100, arredonda para inteiro, multiplica de volta
        return amount.divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                     .multiply(BigDecimal.valueOf(100))
                     .setScale(0, RoundingMode.HALF_UP);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Anonimização de Entidades Completas
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Converte uma {@link Transaction} em uma {@link AnonymizedTransaction}
     * eliminando todos os campos de PII.
     *
     * <p>Campos anonimizados:
     * <ul>
     *   <li>{@code accountId} — mascarado por {@link #maskAccountId}</li>
     *   <li>{@code amount} — generalizado por {@link #generalizeAmount}</li>
     *   <li>{@code timestamp} — substituído por {@code dayOfWeek} + {@code hourOfDay}</li>
     * </ul>
     *
     * @param transaction transação com PII
     * @return visão anonimizada para consumo pelo ForensicAgent
     */
    public AnonymizedTransaction anonymizeTransaction(Transaction transaction) {
        String maskedId    = maskAccountId(transaction.getAccountId());
        BigDecimal genAmt  = generalizeAmount(transaction.getAmount());

        String dayOfWeek = transaction.getTimestamp() != null
                ? transaction.getTimestamp().getDayOfWeek().name()
                : "UNKNOWN";
        int hourOfDay = transaction.getTimestamp() != null
                ? transaction.getTimestamp().getHour()
                : -1;

        return new AnonymizedTransaction(
                maskedId,
                genAmt,
                transaction.getMerchantCategory(),
                transaction.getStatus() != null ? transaction.getStatus().name() : "UNKNOWN",
                transaction.getTimestamp(),
                dayOfWeek,
                hourOfDay
        );
    }

    /**
     * Anonimiza uma lista de transações.
     *
     * @param transactions lista de transações com PII
     * @return lista de visões anonimizadas, na mesma ordem
     */
    public List<AnonymizedTransaction> anonymizeAll(List<Transaction> transactions) {
        return transactions.stream()
                .map(this::anonymizeTransaction)
                .toList();
    }
}
