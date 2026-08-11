package com.sentinelops.core;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita o subsistema de scheduling do Spring para o SentinelOps.
 *
 * <p>Sem esta anotação, os métodos anotados com {@code @Scheduled}
 * (como {@link com.sentinelops.agents.TriageScheduler#dispatchPendingTransactions()})
 * são ignorados silenciosamente pelo Spring — um erro comum e difícil de depurar.
 *
 * <p>Mantida em classe separada (e não na {@code SentinelOpsApplication}) para
 * facilitar a desabilitação do scheduling em perfis de teste via
 * {@code @TestPropertySource(properties = "spring.task.scheduling.pool.size=0")}.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
    // Bean de configuração — nenhum método necessário.
    // A presença da anotação @EnableScheduling é suficiente para registrar
    // o TaskScheduler default no contexto Spring.
}
