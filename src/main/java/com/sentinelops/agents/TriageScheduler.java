package com.sentinelops.agents;

import com.sentinelops.core.Transaction;
import com.sentinelops.core.TransactionRepository;
import com.sentinelops.core.TransactionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduler assíncrono que alimenta o {@link TriageAgent} com transações pendentes.
 *
 * <h2>Mecanismo de Polling</h2>
 * <p>A cada ciclo (fixedDelay = 5000ms), o scheduler consulta o SQLite por todas
 * as transações com status {@link TransactionStatus#PENDING} e despacha cada uma
 * para o {@link TriageAgent#processTriage(java.util.UUID)}.
 *
 * <p><b>Por que {@code fixedDelay} e não {@code fixedRate}?</b><br>
 * {@code fixedDelay} garante que a próxima execução só inicia após a conclusão
 * da execução anterior. Isso previne sobreposição de ciclos em caso de lentidão
 * da API Gemini ou do banco — essencial para o modelo de threading único do SQLite.
 *
 * <h2>Proteção Contra Duplo Processamento</h2>
 * <p>O {@link TriageAgent#processTriage} verifica internamente se a transação
 * ainda está em {@code PENDING} antes de processá-la. Isso garante idempotência
 * caso o mesmo scheduler rode em múltiplas instâncias no futuro.
 *
 * <h2>Configuração</h2>
 * <p>Requer {@code @EnableScheduling} no contexto Spring — habilitado via
 * {@link com.sentinelops.core.SchedulingConfig}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriageScheduler {

    private final TransactionRepository transactionRepository;
    private final TriageAgent           triageAgent;

    /**
     * Ciclo principal do scheduler de triagem.
     *
     * <p>Executado automaticamente pelo Spring TaskScheduler a cada 5 segundos
     * após o término do ciclo anterior. O método é síncrono — a execução do
     * Spring aguarda sua conclusão antes de agendar o próximo ciclo.
     *
     * <p>Cada transação PENDING é processada sequencialmente dentro do ciclo
     * para garantir a integridade das atualizações no SQLite (sem concorrência
     * de writes paralelos).
     */
    @Scheduled(fixedDelay = 5000)
    public void dispatchPendingTransactions() {
        List<Transaction> pendingTransactions =
                transactionRepository.findByStatusOrderByTimestampDesc(TransactionStatus.PENDING);

        if (pendingTransactions.isEmpty()) {
            log.debug("[TriageScheduler] Nenhuma transação PENDING encontrada — aguardando próximo ciclo.");
            return;
        }

        log.info("[TriageScheduler] ► Ciclo iniciado | {} transação(ões) PENDING encontrada(s)",
                pendingTransactions.size());

        int processed = 0;
        int failed    = 0;

        for (Transaction tx : pendingTransactions) {
            try {
                log.info("[TriageScheduler] Despachando → TriageAgent | transactionId={} | accountId={} | amount={}",
                        tx.getId(), tx.getAccountId(), tx.getAmount());

                triageAgent.processTriage(tx.getId());
                processed++;

            } catch (Exception e) {
                // Captura qualquer exceção não tratada para que o scheduler
                // continue processando as demais transações do ciclo.
                // A transação permanece PENDING e será retentada no próximo ciclo.
                failed++;
                log.error("[TriageScheduler] Falha ao processar transação | id={} | erro={}",
                        tx.getId(), e.getMessage(), e);
            }
        }

        log.info("[TriageScheduler] ✓ Ciclo concluído | processadas={} | falhas={} | total={}",
                processed, failed, pendingTransactions.size());
    }
}
