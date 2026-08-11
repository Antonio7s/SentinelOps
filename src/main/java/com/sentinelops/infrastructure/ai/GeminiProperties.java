package com.sentinelops.infrastructure.ai;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Propriedades de configuração para a API do Gemini.
 *
 * <p>Os valores são injetados automaticamente a partir do prefixo {@code gemini}
 * no {@code application.yml}. O uso de {@link ConfigurationProperties} é preferível
 * a {@code @Value} em cenários com múltiplas propriedades relacionadas.
 *
 * <p>Exemplo de uso no YAML:
 * <pre>
 * gemini:
 *   api-key: "sua-chave-aqui"
 *   base-url: "https://generativelanguage.googleapis.com/v1beta"
 *   model: "gemini-3.5-pro"
 * </pre>
 */
@Getter
@Validated
@Configuration
@ConfigurationProperties(prefix = "gemini")
public class GeminiProperties {

    /**
     * Chave de API para autenticação no Google AI Studio / Vertex AI.
     * Em produção, injete via variável de ambiente {@code GEMINI_API_KEY}.
     */
    private String apiKey;

    /**
     * URL base da REST API do Gemini (sem barra final).
     */
    private String baseUrl;

    /**
     * Identificador do modelo Gemini a ser usado nas chamadas
     * (ex: {@code gemini-2.5-flash}, {@code gemini-1.5-pro}).
     */
    private String model;

    /**
     * Timeout de conexão HTTP em segundos.
     */
    private int connectTimeoutSeconds = 10;

    /**
     * Timeout de leitura HTTP em segundos.
     */
    private int readTimeoutSeconds = 60;

    // ---------------------------------------------------------------------------
    // Setters necessários para o binding do @ConfigurationProperties
    // (Lombok @Getter não gera setters; definidos manualmente para clareza)
    // ---------------------------------------------------------------------------

    public void setApiKey(String apiKey)                         { this.apiKey = apiKey; }
    public void setBaseUrl(String baseUrl)                       { this.baseUrl = baseUrl; }
    public void setModel(String model)                           { this.model = model; }
    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
    public void setReadTimeoutSeconds(int readTimeoutSeconds)    { this.readTimeoutSeconds = readTimeoutSeconds; }
}
