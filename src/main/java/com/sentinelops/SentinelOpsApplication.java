package com.sentinelops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ponto de entrada do SentinelOps.
 *
 * <p>A anotação {@code @SpringBootApplication} combina:
 * <ul>
 *   <li>{@code @Configuration} — define a classe como fonte de beans.</li>
 *   <li>{@code @EnableAutoConfiguration} — ativa a auto-configuração do Spring Boot.</li>
 *   <li>{@code @ComponentScan} — varre todos os sub-pacotes de {@code com.sentinelops}.</li>
 * </ul>
 */
@SpringBootApplication
public class SentinelOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SentinelOpsApplication.class, args);
    }
}
