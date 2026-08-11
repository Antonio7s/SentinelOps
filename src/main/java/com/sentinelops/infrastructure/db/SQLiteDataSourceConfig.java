package com.sentinelops.infrastructure.db;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * Configuração explícita do DataSource SQLite.
 *
 * <p>O Spring Boot auto-configura o DataSource via propriedades do
 * {@code application.yml}, portanto esta classe é <strong>opcional</strong>
 * e só é ativada se a propriedade {@code sentinelops.db.explicit-config=true}
 * estiver presente. Isso permite customizações avançadas sem sobrescrever
 * o comportamento padrão durante o desenvolvimento.
 *
 * <p>Para uso normal no hackathon, a auto-configuração do YAML é suficiente.
 * Mantenha esta classe como referência para configurações avançadas futuras
 * (ex: múltiplos datasources, pool customizado, WAL mode do SQLite).
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "sentinelops.db.explicit-config", havingValue = "true")
public class SQLiteDataSourceConfig {

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    /**
     * DataSource explícito com suporte a modo WAL (Write-Ahead Logging) do SQLite.
     *
     * <p>O WAL mode melhora significativamente a performance em cenários
     * concorrentes (múltiplos agentes lendo/escrevendo simultaneamente).
     *
     * <p><b>ATIVAÇÃO:</b> adicione ao {@code application.yml}:
     * <pre>
     * sentinelops:
     *   db:
     *     explicit-config: true
     * </pre>
     *
     * @return DataSource configurado para SQLite com WAL mode
     */
    @Bean
    public DataSource sqliteDataSource() {
        log.info("[SQLiteDataSourceConfig] Criando DataSource explícito | url={}", datasourceUrl);

        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl(datasourceUrl);

        // Habilita WAL mode para melhor concorrência com múltiplos agentes
        // Mais informações: https://www.sqlite.org/wal.html
        dataSource.setConnectionProperties(createSQLiteProperties());

        log.info("[SQLiteDataSourceConfig] DataSource SQLite configurado com WAL mode.");
        return dataSource;
    }

    /**
     * Propriedades de conexão SQLite para otimização de performance.
     */
    private java.util.Properties createSQLiteProperties() {
        var props = new java.util.Properties();
        // WAL mode: leituras concorrentes sem bloquear escritas
        props.setProperty("journal_mode", "WAL");
        // Aumenta o timeout de espera por lock (útil com múltiplos agentes)
        props.setProperty("busy_timeout", "5000");
        // Sincronização normal (balanço performance/durabilidade)
        props.setProperty("synchronous", "NORMAL");
        return props;
    }
}
