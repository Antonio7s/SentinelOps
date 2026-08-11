package com.sentinelops.infrastructure.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Cliente de infraestrutura para a API REST do Google Gemini.
 *
 * <h2>Responsabilidades</h2>
 * <ul>
 *   <li>Montar e enviar requisições HTTP para o endpoint {@code generateContent}.</li>
 *   <li>Aplicar resiliência via {@link Retry} do Resilience4j com Exponential Backoff.</li>
 *   <li>Fornecer fallback padronizado quando todas as tentativas falharem.</li>
 * </ul>
 *
 * <h2>Fluxo de Resiliência (HTTP 429)</h2>
 * <pre>
 *  Chamada
 *    │
 *    ▼
 *  [Tentativa 1] ──429──► espera 2s
 *    │
 *    ▼
 *  [Tentativa 2] ──429──► espera 4s (2s × 2¹)
 *    │
 *    ▼
 *  [Tentativa 3] ──429──► espera 8s (2s × 2²)
 *    │
 *    ▼
 *  [Tentativa 4] ──429──► MaxRetriesExceededException
 *    │
 *    ▼
 *  [Fallback] ──► {"status":"MANUAL_REVIEW","reason":"AI_API_UNAVAILABLE"}
 * </pre>
 *
 * <h2>Configuração no application.yml</h2>
 * <pre>
 * resilience4j.retry.instances.geminiApiRetry:
 *   max-attempts: 4
 *   wait-duration: 2s
 *   enable-exponential-backoff: true
 *   exponential-backoff-multiplier: 2.0
 * </pre>
 */
@Slf4j
@Service
public class GeminiApiClient {

    // ─────────────────────────────────────────────────────────────────────────
    // Constantes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Nome do retry instance definido no application.yml.
     * Deve corresponder exatamente à chave {@code resilience4j.retry.instances.<name>}.
     */
    private static final String RETRY_INSTANCE_NAME = "geminiApiRetry";

    /**
     * Resposta JSON de fallback retornada quando todas as tentativas falham.
     * Indica ao sistema que a resposta requer revisão manual.
     */
    private static final String FALLBACK_RESPONSE = """
            {
              "status": "MANUAL_REVIEW",
              "reason": "AI_API_UNAVAILABLE",
              "message": "O serviço Gemini não respondeu após múltiplas tentativas. Revisão humana necessária.",
              "retryExhausted": true
            }
            """;

    // ─────────────────────────────────────────────────────────────────────────
    // Dependências
    // ─────────────────────────────────────────────────────────────────────────

    private final RestClient restClient;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;

    public GeminiApiClient(
            GeminiRestClientConfig restClientConfig,
            GeminiProperties properties,
            ObjectMapper objectMapper) {

        this.restClient   = restClientConfig.buildRestClient();
        this.properties   = properties;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API Pública
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Envia um prompt de texto simples ao Gemini e retorna a resposta bruta em JSON.
     *
     * <p>O método é decorado com {@link Retry} usando a instância {@value #RETRY_INSTANCE_NAME},
     * que aplica Exponential Backoff apenas para {@link GeminiRateLimitException} (HTTP 429).
     *
     * <p>Se todas as tentativas falharem, o método {@link #generateContentFallback} é invocado
     * automaticamente pelo Resilience4j.
     *
     * @param prompt texto de entrada a ser processado pelo modelo
     * @return resposta JSON do Gemini como {@link String}, ou o fallback em caso de falha persistente
     */
    @Retry(name = RETRY_INSTANCE_NAME, fallbackMethod = "generateContentFallback")
    public String generateContent(String prompt) {
        log.info("[GeminiApiClient] Enviando prompt ao Gemini | model={} | promptLength={}",
                properties.getModel(), prompt.length());

        // ── Monta o payload da requisição no formato da API Gemini ─────────
        var requestBody = buildRequestBody(prompt);

        // ── Caminho do endpoint (nova API interactions) ──────────
        var path = "/interactions";

        // ── Executa a requisição HTTP síncrona ────────────────────────────
        String responseBody = restClient
                .post()
                .uri(path)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        log.debug("[GeminiApiClient] Resposta recebida com sucesso | model={}", properties.getModel());
        return responseBody;
    }

    /**
     * Envia uma conversa multi-turn ao Gemini (histórico de mensagens).
     *
     * <p>Útil para agentes que mantêm contexto entre interações.
     * Aplica as mesmas políticas de retry que {@link #generateContent(String)}.
     *
     * @param contents lista de mensagens no formato {@code [{role, parts}]}
     * @return resposta JSON do Gemini como {@link String}, ou o fallback em caso de falha persistente
     */
    @Retry(name = RETRY_INSTANCE_NAME, fallbackMethod = "generateContentMultiTurnFallback")
    public String generateContentMultiTurn(List<Map<String, Object>> contents) {
        log.info("[GeminiApiClient] Enviando conversa multi-turn | model={} | turns={}",
                properties.getModel(), contents.size());

        // TODO: O formato de payload multi-turn para a nova API de interactions ainda não foi validado com uma chamada real.
        // Cuidado: isso pode falhar se o formato esperado for diferente da API antiga.
        var requestBody = Map.of("contents", contents);
        var path        = "/interactions";

        String responseBody = restClient
                .post()
                .uri(path)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        log.debug("[GeminiApiClient] Resposta multi-turn recebida | model={}", properties.getModel());
        return responseBody;
    }

    /**
     * Extrai somente o texto gerado pelo Gemini da resposta JSON bruta
     * no novo formato da API /interactions.
     *
     * @param geminiJsonResponse resposta JSON bruta retornada por {@link #generateContent}
     * @return texto extraído, ou mensagem de erro se a estrutura for inválida
     */
    public String extractTextFromResponse(String geminiJsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(geminiJsonResponse);

            // Verifica se é uma resposta de fallback (sem campo "steps")
            if (root.has("status") && "MANUAL_REVIEW".equals(root.get("status").asText())) {
                return "[FALLBACK] " + root.get("message").asText();
            }

            JsonNode steps = root.path("steps");
            if (steps.isArray()) {
                for (JsonNode step : steps) {
                    if ("model_output".equals(step.path("type").asText())) {
                        JsonNode contentArray = step.path("content");
                        if (contentArray.isArray()) {
                            for (JsonNode item : contentArray) {
                                if ("text".equals(item.path("type").asText())) {
                                    return item.path("text").asText();
                                }
                            }
                        }
                    }
                }
            }
            
            throw new GeminiNonRetryableException("Resposta sem model_output válido");

        } catch (JsonProcessingException e) {
            log.error("[GeminiApiClient] Falha ao parsear resposta JSON do Gemini", e);
            return "[ERRO DE PARSE] Resposta inválida recebida da API Gemini.";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Métodos de Fallback (invocados pelo Resilience4j após max-attempts)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fallback para {@link #generateContent(String)}.
     *
     * <p><b>Contrato do Resilience4j:</b> o método de fallback deve ter a mesma
     * assinatura do método principal, acrescida de um parâmetro {@link Throwable}
     * no final. O nome deve ser passado explicitamente em {@code @Retry(fallbackMethod)}.
     *
     * @param prompt    o prompt original que causou a falha (para logging)
     * @param throwable a exceção que esgotou todas as tentativas
     * @return JSON padrão indicando necessidade de revisão manual
     */
    public String generateContentFallback(String prompt, Throwable throwable) {
        log.error(
                "[GeminiApiClient] FALLBACK ATIVADO — Todas as {} tentativas falharam. "
                + "Prompt (primeiros 100 chars): '{}' | Causa: {}",
                4,
                prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt,
                throwable.getMessage());

        return FALLBACK_RESPONSE;
    }

    /**
     * Fallback para {@link #generateContentMultiTurn(List)}.
     *
     * @param contents  histórico de mensagens original
     * @param throwable a exceção que esgotou todas as tentativas
     * @return JSON padrão indicando necessidade de revisão manual
     */
    public String generateContentMultiTurnFallback(
            List<Map<String, Object>> contents, Throwable throwable) {

        log.error(
                "[GeminiApiClient] FALLBACK MULTI-TURN ATIVADO — {} mensagens perdidas. Causa: {}",
                contents.size(),
                throwable.getMessage());

        return FALLBACK_RESPONSE;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers Privados
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Monta o corpo da requisição no formato esperado pela API Gemini
     * para prompts de texto simples (single-turn) via /interactions.
     *
     * <p>Estrutura resultante:
     * <pre>
     * {
     *   "model": "gemini-3.6-flash",
     *   "input": "..."
     * }
     * </pre>
     *
     * @param prompt texto do usuário
     * @return mapa representando o corpo JSON
     */
    private Map<String, Object> buildRequestBody(String prompt) {
        return Map.of(
            "model", properties.getModel(),
            "input", prompt
        );
    }
}
