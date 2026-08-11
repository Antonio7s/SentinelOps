package com.sentinelops.infrastructure.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Fábrica de configuração do {@link RestClient} para comunicação com a API Gemini.
 *
 * <p>Centraliza em um único lugar:
 * <ul>
 *   <li>Configuração de timeouts (conexão e leitura).</li>
 *   <li>Base URL injetada via {@link GeminiProperties}.</li>
 *   <li>Header {@code x-goog-api-key} enviado globalmente em todas as requisições.</li>
 *   <li>Tratamento de erros HTTP: mapeia status HTTP para exceções tipadas.</li>
 * </ul>
 *
 * <p><b>Por que RestClient e não RestTemplate?</b><br>
 * O {@code RestClient} é a API fluente moderna do Spring 6.1+, substituto
 * recomendado do {@code RestTemplate}. Mantém sincronia (compatível com
 * thread virtual do Java 21) sem necessidade de WebFlux.
 */
@Slf4j
@Component
public class GeminiRestClientConfig {

    private final GeminiProperties properties;

    public GeminiRestClientConfig(GeminiProperties properties) {
        this.properties = properties;
    }

    /**
     * Cria e retorna um {@link RestClient} pré-configurado para o Gemini.
     *
     * <p>Este bean é produzido por {@link GeminiApiClient} via injeção desta classe,
     * mantendo a responsabilidade de configuração separada da lógica de negócio.
     *
     * @return instância configurada de {@link RestClient}
     */
    public RestClient buildRestClient() {
        log.info("[GeminiRestClient] Inicializando RestClient | baseUrl={} | model={}",
                properties.getBaseUrl(), properties.getModel());

        // ── Configura timeouts no factory subjacente ─────────────────────────
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(
                Duration.ofSeconds(properties.getConnectTimeoutSeconds()));
        requestFactory.setReadTimeout(
                Duration.ofSeconds(properties.getReadTimeoutSeconds()));

        return RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(properties.getBaseUrl())

                // Header de autenticação enviado em todas as requisições
                .defaultHeader("x-goog-api-key", properties.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")

                // ── Mapeamento de status HTTP para exceções tipadas ────────────
                .defaultStatusHandler(
                        // HTTP 429 — Rate Limit: deve ativar o Retry do Resilience4j
                        HttpStatusCode::is4xxClientError,
                        (request, response) -> {
                            int statusCode = response.getStatusCode().value();
                            if (statusCode == 429) {
                                log.warn("[GeminiRestClient] HTTP 429 recebido — Rate limit atingido. "
                                        + "Retry será ativado pelo Resilience4j.");
                                throw new GeminiRateLimitException(
                                        "Gemini API retornou HTTP 429 (Too Many Requests). "
                                        + "Resilience4j ativará o Exponential Backoff Retry.");
                            }
                            // Outros 4xx — erros de negócio não retentáveis
                            log.error("[GeminiRestClient] Erro HTTP {} recebido — sem retry.", statusCode);
                            throw new GeminiNonRetryableException(
                                    "Gemini API retornou erro não retentável: HTTP " + statusCode);
                        })

                .defaultStatusHandler(
                        // HTTP 5xx — Erros de servidor (podem ser retentados no futuro)
                        HttpStatusCode::is5xxServerError,
                        (request, response) -> {
                            int statusCode = response.getStatusCode().value();
                            log.error("[GeminiRestClient] Erro de servidor HTTP {} — sem retry configurado.", statusCode);
                            throw new GeminiNonRetryableException(
                                    "Gemini API retornou erro de servidor: HTTP " + statusCode);
                        })

                .build();
    }
}
