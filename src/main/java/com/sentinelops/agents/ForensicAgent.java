package com.sentinelops.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.agents.ForensicAnalysisResult.AnonymizedTransaction;
import com.sentinelops.core.AgentAuditLog;
import com.sentinelops.core.AgentAuditLogRepository;
import com.sentinelops.core.AgentIdentity;
import com.sentinelops.core.Transaction;
import com.sentinelops.core.TransactionRepository;
import com.sentinelops.infrastructure.ai.GeminiApiClient;
import com.sentinelops.infrastructure.security.AnonymizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agente Forense — segunda linha de análise do pipeline SentinelOps.
 *
 * <p>Ativado pelo {@link TriageAgent} quando a triagem retorna {@code MANUAL_REVIEW}.
 * Investiga o histórico comportamental da conta para detectar anomalias que
 * indicam fraude sofisticada ou comprometimento de conta.
 *
 * <h2>Pilar Zero-Trust: Agent Identity + Data Minimization</h2>
 * <p>O ForensicAgent opera exclusivamente sob a identidade
 * {@link AgentIdentity#FORENSIC_AGENT} com permissão
 * {@code READ_ANONYMIZED_HISTORY}. Ele <strong>nunca</strong> recebe
 * o {@code accountId} real — todos os dados são anonimizados pelo
 * {@link AnonymizationService} antes de serem entregues ao agente ou
 * enviados ao modelo Gemini.
 *
 * <h2>Fluxo de Análise</h2>
 * <pre>
 *  analyzeHistory(transactionId)
 *       │
 *       ▼
 *  [1] requirePermission("READ_ANONYMIZED_HISTORY")  ← Zero-Trust gate
 *       │
 *       ▼
 *  [2] Busca transação atual + 10 mais recentes da conta
 *       │
 *       ▼
 *  [3] AnonymizationService.anonymizeAll()  ← PII removida aqui
 *       │
 *       ▼
 *  [4] Monta prompt forense com histórico anonimizado
 *       │
 *       ▼
 *  [5] GeminiApiClient.generateContent()  ← Resilience4j retry ativo
 *       │
 *       ▼
 *  [6] Parseia ForensicAnalysisResult
 *       │
 *       ▼
 *  [7] Persiste AgentAuditLog com tag "FORENSIC_AGENT_01"
 *       │
 *       ▼
 *  [8] Retorna ForensicAnalysisResult ao TriageAgent
 * </pre>
 *
 * <h2>Anomalias Detectadas pelo Modelo</h2>
 * <ul>
 *   <li>Desvio de valor em relação à média histórica (ex: 3× acima da média)</li>
 *   <li>Mudança brusca de categoria de merchant (ex: sempre GROCERY → JEWELRY)</li>
 *   <li>Padrão temporal suspeito (ex: todas as transações anteriores em horário comercial,
 *       esta às 03h00)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ForensicAgent {

    // ─────────────────────────────────────────────────────────────────────────
    // Identidade e configuração
    // ─────────────────────────────────────────────────────────────────────────

    /** Identidade Zero-Trust deste agente. Imutável e verificada em toda chamada. */
    private static final AgentIdentity IDENTITY = AgentIdentity.FORENSIC_AGENT;

    static final String AGENT_NAME = "ForensicAgent";

    /** Número de transações históricas recuperadas para a janela de contexto. */
    private static final int HISTORY_WINDOW = 10;

    /** Regex para extrair JSON de blocos markdown do Gemini. */
    private static final Pattern JSON_BLOCK_PATTERN =
            Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```", Pattern.CASE_INSENSITIVE);

    // ─────────────────────────────────────────────────────────────────────────
    // Dependências
    // ─────────────────────────────────────────────────────────────────────────

    private final GeminiApiClient         geminiApiClient;
    private final TransactionRepository   transactionRepository;
    private final AgentAuditLogRepository auditLogRepository;
    private final AnonymizationService    anonymizationService;
    private final ObjectMapper            objectMapper;

    // ─────────────────────────────────────────────────────────────────────────
    // API Pública
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Executa a análise forense do histórico comportamental de uma transação.
     *
     * <p>A operação é {@code @Transactional(readOnly = true)} pois o ForensicAgent
     * apenas lê dados. A gravação do {@link AgentAuditLog} é feita em uma
     * transação separada (sem {@code readOnly}) pelo método privado
     * {@link #persistAuditLog}.
     *
     * @param transactionId UUID da transação em estado {@code MANUAL_REVIEW}
     * @return resultado da análise forense, nunca {@code null}
     * @throws SecurityException se a identidade do agente não tiver permissão adequada
     */
    @Transactional(readOnly = true)
    public ForensicAnalysisResult analyzeHistory(UUID transactionId) {
        log.info("[ForensicAgent] ► Iniciando análise forense | transactionId={} | identity={}",
                transactionId, IDENTITY.agentId());

        // ── [1] Validação Zero-Trust: verifica permissão antes de qualquer dado ──
        IDENTITY.requirePermission(AgentIdentity.PERM_READ_ANONYMIZED_HISTORY);
        log.debug("[ForensicAgent] Zero-Trust ✓ | agentId={} | role={} | permissions={}",
                IDENTITY.agentId(), IDENTITY.role(), IDENTITY.permissions());

        // ── [2] Busca transação atual ──────────────────────────────────────────
        Transaction currentTx = transactionRepository.findById(transactionId).orElse(null);
        if (currentTx == null) {
            log.error("[ForensicAgent] Transação não encontrada — análise abortada | id={}", transactionId);
            return buildFallbackResult(transactionId, "Transaction not found in database.");
        }

        // ── [3] Busca histórico e anonimiza — PII nunca sai desta camada ─────
        List<Transaction> history = transactionRepository
                .findRecentByAccountId(currentTx.getAccountId(), HISTORY_WINDOW);

        log.info("[ForensicAgent] Histórico recuperado | conta=MASKED | transações={}", history.size());

        AnonymizedTransaction anonCurrent   = anonymizationService.anonymizeTransaction(currentTx);
        List<AnonymizedTransaction> anonHistory = anonymizationService.anonymizeAll(history);

        log.debug("[ForensicAgent] Anonimização concluída | accountId_mascarado={}",
                anonCurrent.maskedAccountId());

        // ── [4] Monta prompt forense com dados 100% anonimizados ─────────────
        String prompt = buildForensicPrompt(anonCurrent, anonHistory);
        log.debug("[ForensicAgent] Prompt forense construído | chars={}", prompt.length());

        // ── [5] Chama Gemini com Resilience4j ativo ───────────────────────────
        String rawResponse  = geminiApiClient.generateContent(prompt);
        String textContent  = geminiApiClient.extractTextFromResponse(rawResponse);

        // ── [6] Detecta fallback da API e parseia resultado ───────────────────
        ForensicAnalysisResult result;
        if (isFallbackResponse(rawResponse, textContent)) {
            log.error("[ForensicAgent] Gemini indisponível — resultado de fallback aplicado | id={}", transactionId);
            result = buildFallbackResult(transactionId, "Gemini API unavailable during forensic analysis.");
        } else {
            result = parseForensicResult(textContent, transactionId);
        }

        log.info("[ForensicAgent] ✓ Análise concluída | id={} | anomaly={} | score={} | patterns={}",
                transactionId, result.hasHistoricalAnomaly(), result.anomalyScore(), result.detectedPatterns());

        // ── [7] Persiste AuditLog (em nova transação) ─────────────────────────
        persistAuditLog(transactionId, result, anonCurrent, anonHistory.size());

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Construção do Prompt Forense
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Constrói o prompt de análise forense com histórico anonimizado.
     *
     * <p>O prompt é intencionalmente mais longo que o do TriageAgent pois
     * precisa contextualizar o modelo com o histórico completo da conta.
     * Todos os valores enviados ao Gemini já passaram pela camada de anonimização.
     *
     * @param current transação atual (anonimizada)
     * @param history histórico de transações (anonimizadas)
     * @return prompt estruturado pronto para envio
     */
    private String buildForensicPrompt(AnonymizedTransaction current,
                                        List<AnonymizedTransaction> history) {
        String systemPrompt = """
                [SYSTEM PROMPT — FORENSIC FINANCIAL ANALYST]
                Você é um Analista Forense Financeiro Sênior especializado em detecção de \
                anomalias comportamentais em contas bancárias. Você trabalha para o sistema \
                SentinelOps e opera sob protocolos rigorosos de Zero-Trust.
                
                IMPORTANTE: Os dados que você receberá são ANONIMIZADOS. Os valores monetários \
                foram arredondados, o identificador de conta foi mascarado, e as datas foram \
                substituídas por dia-da-semana e hora. Analise apenas os PADRÕES COMPORTAMENTAIS, \
                não os valores absolutos.
                
                Sua tarefa é comparar a TRANSAÇÃO ATUAL com o HISTÓRICO DA CONTA e identificar:
                1. DESVIO_DE_VALOR: Valor significativamente acima ou abaixo da média histórica (>2x ou <0.5x da média)
                2. MUDANCA_DE_CATEGORIA: Categoria de merchant radicalmente diferente do padrão histórico
                3. PADRAO_TEMPORAL_SUSPEITO: Horário ou dia da semana atípico em relação ao histórico
                4. FREQUENCIA_ANOMALA: Transações em intervalos de tempo incomuns
                5. ESCALADA_PROGRESSIVA: Série de transações com valores crescentes (teste de cartão)
                
                REGRAS ABSOLUTAS DE OUTPUT:
                1. Responda EXCLUSIVAMENTE com JSON válido — sem texto antes ou depois.
                2. Não use blocos markdown (sem ``` ou ```json).
                3. O JSON deve conter EXATAMENTE estes campos:
                   - "hasHistoricalAnomaly": boolean (true se qualquer anomalia detectada)
                   - "anomalyScore": número decimal entre 0.0 (normal) e 1.0 (anomalia severa)
                   - "detectedPatterns": array de strings com as constantes de padrão detectadas
                   - "forensicSummary": string STRICTLY IN ENGLISH, forensic summary (maximum 400 characters)
                4. Se não houver histórico suficiente (<3 transações), retorne anomalyScore=0.3 \
                   e inclua "HISTORICO_INSUFICIENTE" em detectedPatterns.
                
                EXEMPLO DE RESPOSTA VÁLIDA:
                {"hasHistoricalAnomaly":true,"anomalyScore":0.82,"detectedPatterns":["DESVIO_DE_VALOR","PADRAO_TEMPORAL_SUSPEITO"],"forensicSummary":"Account with history of low value daytime purchases presents nighttime transaction 4x above average. Highly suspicious pattern."}
                """;

        // Serializa histórico anonimizado como tabela texto estruturada
        StringBuilder historyStr = new StringBuilder();
        if (history.isEmpty()) {
            historyStr.append("  (Nenhuma transação histórica encontrada para esta conta)");
        } else {
            for (int i = 0; i < history.size(); i++) {
                AnonymizedTransaction h = history.get(i);
                historyStr.append(String.format(
                        "  [%02d] Conta=%s | Valor~R$%s | Categoria=%s | %s %02dh | Status=%s%n",
                        i + 1,
                        h.maskedAccountId(),
                        h.amount().toPlainString(),
                        h.merchantCategory(),
                        h.dayOfWeek(),
                        h.hourOfDay(),
                        h.status()
                ));
            }
        }

        String userPrompt = String.format("""
                [USER PROMPT — DADOS FORENSES PARA ANÁLISE]
                
                === TRANSAÇÃO ATUAL (sob investigação) ===
                Conta (mascarada): %s
                Valor (~arredondado): R$ %s
                Categoria:  %s
                Dia da semana: %s
                Hora do dia:   %02dh
                Status atual:  %s
                
                === HISTÓRICO DA CONTA (últimas %d transações, todas anonimizadas) ===
                %s
                
                Com base na comparação entre a transação atual e o histórico acima, \
                identifique anomalias comportamentais e retorne o JSON de análise forense.
                """,
                current.maskedAccountId(),
                current.amount().toPlainString(),
                current.merchantCategory(),
                current.dayOfWeek(),
                current.hourOfDay(),
                current.status(),
                HISTORY_WINDOW,
                historyStr
        );

        return systemPrompt + "\n" + userPrompt;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parsing do Resultado
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parseia o JSON de resposta do Gemini em {@link ForensicAnalysisResult}.
     * Aplica a mesma estratégia de 3 camadas do TriageAgent para robustez.
     */
    private ForensicAnalysisResult parseForensicResult(String textContent, UUID transactionId) {
        // Tentativa 1: parse direto
        try {
            return parseFromJson(textContent.trim(), transactionId);
        } catch (Exception e) {
            log.debug("[ForensicAgent] Parse direto falhou | id={}", transactionId);
        }

        // Tentativa 2: strip markdown
        String cleaned = stripMarkdownBlock(textContent);
        try {
            return parseFromJson(cleaned.trim(), transactionId);
        } catch (Exception e) {
            log.debug("[ForensicAgent] Parse após strip markdown falhou | id={}", transactionId);
        }

        // Tentativa 3: extrai primeiro {...}
        String extracted = extractJsonObject(cleaned);
        if (extracted != null) {
            try {
                return parseFromJson(extracted.trim(), transactionId);
            } catch (Exception e) {
                log.warn("[ForensicAgent] Todas as tentativas de parse falharam | id={} | preview={}",
                        transactionId, textContent.substring(0, Math.min(200, textContent.length())));
            }
        }

        log.error("[ForensicAgent] Parse impossível — retornando resultado de segurança | id={}", transactionId);
        return buildFallbackResult(transactionId,
                "ForensicAgent response could not be interpreted. Human review mandatory.");
    }

    /**
     * Converte o JSON bruto do Gemini no record {@link ForensicAnalysisResult}.
     * Extrai cada campo individualmente para máxima tolerância a variações de formato.
     */
    private ForensicAnalysisResult parseFromJson(String json, UUID transactionId)
            throws JsonProcessingException {

        JsonNode root = objectMapper.readTree(json);

        boolean hasAnomaly = root.path("hasHistoricalAnomaly").asBoolean(false);
        double  score      = root.path("anomalyScore").asDouble(0.5);
        String  summary    = root.path("forensicSummary").asText("Sem sumário.");

        List<String> patterns = new ArrayList<>();
        JsonNode patternsNode = root.path("detectedPatterns");
        if (patternsNode.isArray()) {
            patternsNode.forEach(node -> patterns.add(node.asText()));
        }

        return new ForensicAnalysisResult(
                hasAnomaly, score, patterns, summary,
                IDENTITY.agentId(), transactionId, LocalDateTime.now()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Persistência do AuditLog
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Persiste o {@link AgentAuditLog} com a tag de identidade {@code FORENSIC_AGENT_01}
     * e o resultado forense completo serializado no campo {@code thoughtProcess}.
     *
     * <p>Nota: O {@code thoughtProcess} inclui a identidade do agente ({@code agentId},
     * {@code role}, {@code permissions}) para auditoria do pipeline Zero-Trust.
     *
     * @param transactionId UUID da transação analisada
     * @param result        resultado da análise forense
     * @param anonCurrent   visão anonimizada da transação (para contexto no log)
     * @param historySize   número de transações históricas analisadas
     */
    @Transactional
    public void persistAuditLog(UUID transactionId,
                                 ForensicAnalysisResult result,
                                 AnonymizedTransaction anonCurrent,
                                 int historySize) {
        try {
            // Monta o thoughtProcess com metadados de identidade Zero-Trust
            var thoughtNode = objectMapper.createObjectNode();
            thoughtNode.put("agentId",           IDENTITY.agentId());
            thoughtNode.put("role",              IDENTITY.role());
            thoughtNode.put("permissions",       IDENTITY.permissions().toString());
            thoughtNode.put("historyWindowSize", historySize);
            thoughtNode.put("maskedAccountId",   anonCurrent.maskedAccountId());
            thoughtNode.put("hasHistoricalAnomaly", result.hasHistoricalAnomaly());
            thoughtNode.put("anomalyScore",      result.anomalyScore());
            thoughtNode.put("forensicSummary",   result.forensicSummary());
            var patternsArray = thoughtNode.putArray("detectedPatterns");
            result.detectedPatterns().forEach(patternsArray::add);
            thoughtNode.put("analyzedAt",        result.analyzedAt().toString());
            thoughtNode.put("zeroTrustVerified", true);

            String thoughtProcess = objectMapper.writeValueAsString(thoughtNode);

            AgentAuditLog auditLog = AgentAuditLog.builder()
                    .transactionId(transactionId)
                    .agentName(AGENT_NAME)
                    .thoughtProcess(thoughtProcess)
                    .decision(result.hasHistoricalAnomaly()
                            ? "ANOMALY_DETECTED_score=" + result.anomalyScore()
                            : "NO_ANOMALY_DETECTED")
                    .timestamp(LocalDateTime.now())
                    .build();

            auditLogRepository.save(auditLog);

            log.info("[ForensicAgent] AuditLog gravado | transactionId={} | agentId={} | anomaly={} | score={}",
                    transactionId, IDENTITY.agentId(),
                    result.hasHistoricalAnomaly(), result.anomalyScore());

        } catch (JsonProcessingException e) {
            log.error("[ForensicAgent] Falha ao serializar AuditLog | id={}", transactionId, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isFallbackResponse(String rawResponse, String textContent) {
        return (textContent != null && textContent.startsWith("[FALLBACK]"))
                || (rawResponse != null && rawResponse.contains("AI_API_UNAVAILABLE"));
    }

    private ForensicAnalysisResult buildFallbackResult(UUID transactionId, String reason) {
        return new ForensicAnalysisResult(
                true,
                0.5,
                List.of("ANALISE_INDISPONIVEL"),
                reason,
                IDENTITY.agentId(),
                transactionId,
                LocalDateTime.now()
        );
    }

    private String stripMarkdownBlock(String text) {
        if (text == null) return "";
        Matcher m = JSON_BLOCK_PATTERN.matcher(text);
        return m.find() ? m.group(1).trim() : text;
    }

    private String extractJsonObject(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end   = text.lastIndexOf('}');
        return (start != -1 && end > start) ? text.substring(start, end + 1) : null;
    }
}
