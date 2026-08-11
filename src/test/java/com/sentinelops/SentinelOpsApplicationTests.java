package com.sentinelops;

import com.sentinelops.infrastructure.ai.GeminiApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de sanidade do contexto Spring Boot.
 *
 * <p>Verifica que todos os beans são criados corretamente e que
 * a auto-configuração do SQLite e do Resilience4j funcionam sem erros.
 *
 * <p>Usa o perfil {@code test} para evitar dependências externas reais.
 * A chave do Gemini pode ser um placeholder neste contexto.
 */
@SpringBootTest
@ActiveProfiles("test")
class SentinelOpsApplicationTests {

    @Autowired
    private GeminiApiClient geminiApiClient;

    /**
     * Verifica que o contexto Spring carrega sem erros.
     * Se este teste passar, todos os beans (JPA, Resilience4j, RestClient) foram configurados.
     */
    @Test
    void contextLoads() {
        assertThat(geminiApiClient).isNotNull();
    }
}
