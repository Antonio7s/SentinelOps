package com.sentinelops.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.core.AgentAuditLog;
import com.sentinelops.core.AgentAuditLogRepository;
import com.sentinelops.core.Transaction;
import com.sentinelops.core.TransactionIngestionService;
import com.sentinelops.core.TransactionRepository;
import com.sentinelops.core.TransactionStatus;
import com.sentinelops.infrastructure.ai.GeminiApiClient;
import com.sentinelops.infrastructure.security.ModelArmorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agente de Triagem — primeira linha de análise do pipeline SentinelOps.
 *
 * <h2>Responsabilidades</h2>
 * <ol>
 *   <li>Buscar a transação PENDING no banco.</li>
 *   <li>Construir um prompt estruturado (System + User) para o Gemini.</li>
 *   <li>Chamar o {@link GeminiApiClient} com resiliência automática (Retry 429).</li>
 *   <li>Parsear e validar o JSON de resposta do LLM.</li>
 *   <li>Atualizar o status da transação conforme a decisão.</li>
 *   <li>Persistir o {@link AgentAuditLog} para todos os casos.</li>
 * </ol>
 *
 * <h2>Fluxo de Decisão</h2>
 * <pre>
 *  processTriage(transactionId)
 *        │
 *        ▼
 *  [Busca Transação] ── não encontrada ──► log WARN + retorno
 *        │
 *        ▼
 *  [Monta Prompt Gemini]
 *        │
 *        ▼
 *  [GeminiApiClient.generateContent()] ←── Retry Resilience4j (429)
 *        │
 *        ▼
 *  [Detecta Fallback?]
 *     SIM ──► status = MANUAL_REVIEW + audit log "AI_API_UNAVAILABLE"
 *     NÃO ──► [Parseia TriageDecision JSON]
 *                    │
 *          ┌─────────┼──────────┐
 *          ▼         ▼          ▼
 *       APPROVED  BLOCKED  MANUAL_REVIEW
 *          │         │          │
 *        audit     audit    audit log
 *         log       log   + ForensicAgent
 *                          será ativado
 * </pre>
 *
 * <h2>Prompt Engineering</h2>
 * O prompt divide responsabilidades em System Prompt (persona + regras de output)
 * e User Prompt (dados concretos da transação), seguindo as melhores práticas
 * para modelos de chat do Google Gemini.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriageAgent {

    // ─────────────────────────────────────────────────────────────────────────
    // Nome canônico deste agente (usado nos logs e no AgentAuditLog)
    // ─────────────────────────────────────────────────────────────────────────
    static final String AGENT_NAME = "TriageAgent";

    // ─────────────────────────────────────────────────────────────────────────
    // Regex para extrair JSON de blocos markdown ```json ... ``` ou ``` ... ```
    // O Gemini às vezes envolve a resposta em code blocks mesmo quando instruído
    // a não fazê-lo — este padrão lida com ambos os casos.
    // ─────────────────────────────────────────────────────────────────────────
    private static final Pattern JSON_BLOCK_PATTERN =
            Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```", Pattern.CASE_INSENSITIVE);

    // ─────────────────────────────────────────────────────────────────────────
    // Dependências
    // ─────────────────────────────────────────────────────────────────────────
    private final GeminiApiClient             geminiApiClient;
    private final TransactionRepository       transactionRepository;
    private final AgentAuditLogRepository     auditLogRepository;
    private final TransactionIngestionService ingestionService;
    private final ObjectMapper                objectMapper;
    private final ModelArmorService           modelArmorService;

    /**
     * ForensicAgent e ResolutionAgent injetados com {@code @Lazy} via field-injection
     * para quebrar ciclos de inicialização Spring. Não podem ser {@code final}
     * pois {@code @RequiredArgsConstructor} do Lombok não suporta {@code @Lazy}.
     */
    @Autowired @Lazy
    private ForensicAgent    forensicAgent;

    @Autowired @Lazy
    private ResolutionAgent  resolutionAgent;

    /**
     * Auto-referência via proxy Spring, necessária para que chamadas internas a
     * métodos {@code @Transactional} deste bean sejam interceptadas pelo AOP.
     * Sem isso, {@code self.commitTriageDecision()} e {@code self.handleApiFallback()}
     * seriam chamadas diretas ({@code this.x()}) e não passariam pelo proxy de transação.
     */
    @Autowired @Lazy
    private TriageAgent self;

    // ─────────────────────────────────────────────────────────────────────────
    // API Pública
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Orquestra o pipeline de triagem completo.
     *
     * <p><strong>Não é {@code @Transactional}</strong> — é um orquestrador puro.
     * Cada etapa do pipeline executa em sua própria transação JPA, que faz commit
     * antes da próxima etapa iniciar. Isso é obrigatório para SQLite (single-writer):
     * chamar ForensicAgent dentro da transação do TriageAgent causaria SQLITE_BUSY
     * pelo auto-flush do Hibernate antes de SELECTs.
     *
     * <h2>Sequência de Transações</h2>
     * <pre>
     *  [T1] self.commitTriageDecision()  — COMMIT (status + AuditLog do Triage)
     *  [T2] forensicAgent.analyzeHistory() — COMMIT (se MANUAL_REVIEW)
     *  [T3] resolutionAgent.executeFinalResolution() — COMMIT (compliance final)
     * </pre>
     *
     * @param transactionId UUID da transação a ser analisada
     */
    public void processTriage(UUID transactionId) {
        log.info("[TriageAgent] ► Iniciando triagem | transactionId={}", transactionId);

        // ── Passo 1: Buscar transação (leitura simples, sem @Transactional necessário) ──
        Transaction transaction = transactionRepository.findById(transactionId).orElse(null);
        if (transaction == null) {
            log.warn("[TriageAgent] Transação não encontrada — abortando triagem | id={}", transactionId);
            return;
        }

        // Guarda de segurança: não reprocessar transações já decididas
        if (transaction.getStatus() != TransactionStatus.PENDING) {
            log.warn("[TriageAgent] Transação já processada | id={} | status={}",
                    transactionId, transaction.getStatus());
            return;
        }

        // ── Passo 2: Construir prompt (Model Armor sanitiza campos externos) ───
        String prompt = buildTriagePrompt(transaction);
        log.debug("[TriageAgent] Prompt construído | chars={}", prompt.length());

        // ── Passo 3: Chamar Gemini (com resiliência automática) ───────────────
        String rawGeminiResponse = geminiApiClient.generateContent(prompt);
        log.debug("[TriageAgent] Resposta bruta recebida do Gemini");

        // ── Passo 4: Extrair texto ─────────────────────────────────────────
        String textContent = geminiApiClient.extractTextFromResponse(rawGeminiResponse);

        // ── Passo 5: Detectar fallback (API indisponível) ─────────────────
        if (isFallbackResponse(rawGeminiResponse, textContent)) {
            log.error("[TriageAgent] Gemini retornou fallback — API indisponível | id={}", transactionId);
            self.handleApiFallback(transactionId);  // T0: própria transação, commit imediato
            return;
        }

        // ── Passo 6: Parsear JSON de decisão ───────────────────────────
        TriageDecision decision = parseTriageDecision(textContent, transactionId);

        // ── Passo 7: [T1] Commit da decisão de triagem ──────────────────────
        // Chamado via 'self' (proxy Spring) para garantir que o @Transactional
        // inicie e COMITE antes das próximas etapas.
        self.commitTriageDecision(transactionId, decision, textContent);

        // ── Passo 8: [T2] ForensicAgent — só após T1 ter feito commit ────────
        if (decision.isManualReview()) {
            log.info("[TriageAgent] → ESCALANDO para ForensicAgent | id={} | riskScore={}",
                    transactionId, decision.riskScore());
            try {
                ForensicAnalysisResult forensicResult = forensicAgent.analyzeHistory(transactionId);
                log.info("[TriageAgent] ForensicAgent concluiu | id={} | anomaly={} | score={} | patterns={}",
                        transactionId, forensicResult.hasHistoricalAnomaly(),
                        forensicResult.anomalyScore(), forensicResult.detectedPatterns());
                if (forensicResult.isHighRisk()) {
                    log.warn("[TriageAgent] ForensicAgent elevou para BLOCKED | id={} | score={}",
                            transactionId, forensicResult.anomalyScore());
                    ingestionService.updateStatus(transactionId, TransactionStatus.BLOCKED);
                }
            } catch (SecurityException se) {
                log.error("[TriageAgent] Zero-Trust violation no ForensicAgent | id={} | err={}",
                        transactionId, se.getMessage());
            } catch (Exception e) {
                log.error("[TriageAgent] ForensicAgent falhou — mantendo MANUAL_REVIEW | id={} | err={}",
                        transactionId, e.getMessage(), e);
            }
        }

        // ── Passo 9: [T3] ResolutionAgent — compliance final após T2 ────────
        try {
            resolutionAgent.executeFinalResolution(
                    transactionId, decision.reason(), decision.riskScore());
        } catch (Exception e) {
            log.error("[TriageAgent] ResolutionAgent falhou — pipeline continuado | id={} | err={}",
                    transactionId, e.getMessage(), e);
        }

        log.info("[TriageAgent] ✓ Triagem concluída | id={} | decision={} | riskScore={} | reason={}",
                transactionId, decision.decision(), decision.riskScore(), decision.reason());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Construção do Prompt
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Constrói o prompt completo (System + User) para análise de risco.
     *
     * <p>O prompt usa separação clara de papéis para maximizar a precisão
     * do Gemini: o "system" define a persona e as regras de output;
     * o "user" fornece os dados concretos sem ambiguidade.
     *
     * <p>A instrução de JSON puro no System Prompt é crítica — sem ela,
     * o Gemini tende a adicionar texto narrativo antes do JSON.
     *
     * @param transaction transação a ser analisada
     * @return prompt formatado pronto para envio ao Gemini
     */
    private String buildTriagePrompt(Transaction transaction) {
        String systemPrompt = """
                [SYSTEM PROMPT — LEIA COM ATENÇÃO]
                Você é um Analista de Risco Financeiro Sênior com 15 anos de experiência em detecção \
                de fraudes bancárias e cartões de crédito. Você trabalha para o sistema SentinelOps, \
                uma plataforma de segurança financeira de missão crítica.
                
                Sua função é analisar transações financeiras e emitir um veredicto de risco \
                objetivo e fundamentado.
                
                REGRAS ABSOLUTAS DE OUTPUT:
                1. Responda EXCLUSIVAMENTE com um objeto JSON válido — sem texto antes, sem texto depois.
                2. Não use blocos de código markdown (sem ``` ou ```json).
                3. O JSON deve conter EXATAMENTE estes campos:
                   - "decision": string, APENAS um destes valores: "APPROVED", "BLOCKED" ou "MANUAL_REVIEW"
                   - "riskScore": número decimal entre 0.0 (sem risco) e 1.0 (risco máximo)
                   - "reason": string STRICTLY IN ENGLISH, detailed explanation of the decision (maximum 300 characters)
                4. Critérios de decisão:
                   - riskScore 0.0–0.35 → "APPROVED" (padrão normal, sem anomalias)
                   - riskScore 0.35–0.70 → "MANUAL_REVIEW" (padrão suspeito, revisão necessária)
                   - riskScore 0.70–1.0  → "BLOCKED" (alta probabilidade de fraude)
                5. Fatores de risco a considerar: valor absoluto, categoria de merchant, \
                   horário da transação, e padrões históricos implícitos da conta.
                
                EXEMPLO DE RESPOSTA VÁLIDA:
                {"decision":"MANUAL_REVIEW","riskScore":0.62,"reason":"High value transaction in electronics category outside business hours. Requires verification of historical pattern."}
                """;

        // ── Model Armor: sanitiza campos de origem externa antes de embed no prompt ──
        // merchantCategory e accountId vêm da API pública — vetor potencial de injeção
        String safeCategory  = modelArmorService.sanitizeInput(transaction.getMerchantCategory());
        String safeAccountId = modelArmorService.sanitizeInput(transaction.getAccountId());

        String userPrompt = String.format("""
                [USER PROMPT — DADOS DA TRANSAÇÃO PARA ANÁLISE]
                Analise a seguinte transação e emita seu veredicto de risco:
                
                - ID da Transação:    %s
                - ID da Conta:        %s
                - Valor (R$):         %s
                - Categoria Merchant: %s
                - Data/Hora:          %s
                
                Com base nos dados acima, aplique sua expertise e retorne o JSON de decisão.
                """,
                transaction.getId(),
                safeAccountId,
                transaction.getAmount().toPlainString(),
                safeCategory,
                transaction.getTimestamp());

        return systemPrompt + "\n" + userPrompt;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parsing e Validação da Resposta
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Verifica se o Gemini retornou o payload de fallback (API indisponível).
     *
     * <p>O fallback é identificado pela presença do campo {@code "status": "MANUAL_REVIEW"}
     * combinado com {@code "reason": "AI_API_UNAVAILABLE"} — formato definido em
     * {@link GeminiApiClient#FALLBACK_RESPONSE}.
     *
     * @param rawResponse  resposta bruta do Gemini (estrutura de candidatos)
     * @param textContent  texto extraído da resposta
     * @return {@code true} se for uma resposta de fallback
     */
    private boolean isFallbackResponse(String rawResponse, String textContent) {
        // O fallback é identificado pelo prefixo [FALLBACK] injetado pelo extractTextFromResponse
        if (textContent != null && textContent.startsWith("[FALLBACK]")) {
            return true;
        }
        // Segunda verificação: o JSON bruto do fallback contém AI_API_UNAVAILABLE
        return rawResponse != null && rawResponse.contains("AI_API_UNAVAILABLE");
    }

    /**
     * Parseia o texto de resposta do Gemini em um {@link TriageDecision}.
     *
     * <p>Estratégia de parsing em camadas:
     * <ol>
     *   <li>Tenta parsear diretamente como JSON.</li>
     *   <li>Remove blocos markdown (```json ... ```) e tenta novamente.</li>
     *   <li>Extrai o primeiro objeto JSON via busca de chaves.</li>
     *   <li>Em caso de falha total, retorna decisão de segurança (MANUAL_REVIEW).</li>
     * </ol>
     *
     * @param textContent   texto extraído da resposta do Gemini
     * @param transactionId UUID da transação (para logging)
     * @return {@link TriageDecision} parseada ou decisão de segurança em falha
     */
    private TriageDecision parseTriageDecision(String textContent, UUID transactionId) {
        // Tentativa 1: parse direto
        try {
            return objectMapper.readValue(textContent.trim(), TriageDecision.class);
        } catch (JsonProcessingException e) {
            log.debug("[TriageAgent] Parse direto falhou, tentando limpar markdown | id={}", transactionId);
        }

        // Tentativa 2: remover blocos markdown
        String cleaned = stripMarkdownCodeBlock(textContent);
        try {
            return objectMapper.readValue(cleaned.trim(), TriageDecision.class);
        } catch (JsonProcessingException e) {
            log.debug("[TriageAgent] Parse após limpeza markdown falhou, tentando extração de JSON | id={}", transactionId);
        }

        // Tentativa 3: extrair primeiro objeto JSON {...} da resposta
        String extracted = extractJsonObject(cleaned);
        if (extracted != null) {
            try {
                return objectMapper.readValue(extracted.trim(), TriageDecision.class);
            } catch (JsonProcessingException e) {
                log.warn("[TriageAgent] Todas as tentativas de parse falharam | id={} | content={}",
                        transactionId, textContent.substring(0, Math.min(200, textContent.length())));
            }
        }

        // Fallback de segurança: MANUAL_REVIEW para não perder transações
        log.error("[TriageAgent] Impossível parsear resposta do Gemini — aplicando MANUAL_REVIEW de segurança | id={}", transactionId);
        return new TriageDecision(
                "MANUAL_REVIEW",
                0.5,
                "Model response could not be interpreted. Manual review mandatory."
        );
    }

    /**
     * Remove delimitadores de bloco markdown da resposta do Gemini.
     *
     * @param text texto potencialmente contendo ```json ... ```
     * @return texto limpo sem blocos markdown
     */
    private String stripMarkdownCodeBlock(String text) {
        if (text == null) return "";
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return text;
    }

    /**
     * Extrai o primeiro objeto JSON {@code {...}} encontrado no texto.
     * Útil quando o Gemini adiciona texto narrativo antes ou depois do JSON.
     *
     * @param text texto com possível JSON embutido
     * @return substring JSON ou {@code null} se não encontrado
     */
    private String extractJsonObject(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end   = text.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Aplicação de Decisão e Persistência
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Aplica a decisão da IA: atualiza o status da transação e persiste o AuditLog.
     *
     * <p>O AuditLog é sempre gravado, independente da decisão — isso garante
     * rastreabilidade completa de todas as análises do TriageAgent.
     * O ForensicAgent (fase futura) usará o log de MANUAL_REVIEW como ponto de partida.
     *
     * @param transaction transação a atualizar
     * @param decision    decisão parseada do Gemini
    /**
     * [T1] Persiste a decisão da triagem em transação JPA própria.
     *
     * <p>Chamado via proxy Spring ({@code self.commitTriageDecision()}) para garantir
     * que o commit ocorra <em>antes</em> do ForensicAgent e ResolutionAgent
     * iniciarem suas próprias transações. Essa separção é obrigatória com
     * SQLite (single-writer) para evitar {@code SQLITE_BUSY} causado pelo
     * auto-flush do Hibernate durante SELECTs de agentes subsequentes.
     *
     * @param transactionId UUID da transação
     * @param decision      decisão parseada do Gemini
     * @param rawThought    texto bruto do modelo (inspecionado pelo Model Armor antes de persistir)
     */
    @Transactional
    public void commitTriageDecision(UUID transactionId, TriageDecision decision, String rawThought) {
        Transaction transaction = transactionRepository.findById(transactionId).orElse(null);
        if (transaction == null) {
            log.error("[TriageAgent] Transação não encontrada em commitTriageDecision | id={}", transactionId);
            return;
        }

        TransactionStatus newStatus = mapDecisionToStatus(decision.decision());
        ingestionService.updateStatus(transactionId, newStatus);

        AgentAuditLog auditLog = AgentAuditLog.builder()
                .transactionId(transactionId)
                .agentName(AGENT_NAME)
                .thoughtProcess(buildThoughtProcessJson(decision, rawThought))
                .decision(decision.decision())
                .timestamp(LocalDateTime.now())
                .build();
        auditLogRepository.save(auditLog);

        log.info("[TriageAgent] AuditLog gravado | transactionId={} | decision={} | riskScore={}",
                transactionId, decision.decision(), decision.riskScore());

        if (decision.isBlocked()) {
            log.warn("[TriageAgent] ⚠ TRANSAÇÃO BLOQUEADA | id={} | accountId={} | amount={} | reason={}",
                    transactionId, transaction.getAccountId(),
                    transaction.getAmount(), decision.reason());
        }
    }

    /**
     * [T0] Marca transação como MANUAL_REVIEW quando a API Gemini está indisponível.
     * Executa em transação JPA própria (chamada via proxy Spring com {@code self}).
     *
     * @param transactionId UUID da transação a ser marcada para revisão
     */
    @Transactional
    public void handleApiFallback(UUID transactionId) {
        ingestionService.updateStatus(transactionId, TransactionStatus.MANUAL_REVIEW);

        AgentAuditLog auditLog = AgentAuditLog.builder()
                .transactionId(transactionId)
                .agentName(AGENT_NAME)
                .thoughtProcess("""
                        {"fallback":true,"reason":"AI_API_UNAVAILABLE",\
                        "message":"Gemini API unavailable after multiple attempts.\
                        Automatic triage was not possible."}""")
                .decision("MANUAL_REVIEW")
                .timestamp(LocalDateTime.now())
                .build();

        auditLogRepository.save(auditLog);

        log.error("[TriageAgent] Fallback aplicado — transação marcada para MANUAL_REVIEW | id={}",
                transactionId);
    }

    /**
     * Mapeia a string de decisão do LLM para o enum {@link TransactionStatus}.
     *
     * @param decision string retornada pelo Gemini
     * @return status correspondente (MANUAL_REVIEW como safe default)
     */
    private TransactionStatus mapDecisionToStatus(String decision) {
        return switch (decision.toUpperCase()) {
            case "APPROVED"      -> TransactionStatus.APPROVED;
            case "BLOCKED"       -> TransactionStatus.BLOCKED;
            case "MANUAL_REVIEW" -> TransactionStatus.MANUAL_REVIEW;
            default -> {
                log.warn("[TriageAgent] Decisão desconhecida '{}' — aplicando MANUAL_REVIEW", decision);
                yield TransactionStatus.MANUAL_REVIEW;
            }
        };
    }

    /**
     * Serializa o {@link TriageDecision} e o texto bruto em um JSON estruturado
     * para armazenamento no {@code thoughtProcess} do AuditLog.
     *
     * <p>Isso preserva tanto a decisão parseada (campos tipados) quanto o
     * texto bruto original do Gemini (para auditoria e debugging).
     *
     * @param decision   decisão parseada
     * @param rawThought texto bruto retornado pelo modelo
     * @return JSON string formatada para persistência
     */
    private String buildThoughtProcessJson(TriageDecision decision, String rawThought) {
        try {
            // ── Model Armor: inspeciona output do Gemini antes de persistir no AuditLog ──
            // Garante que nenhum PII vaze da resposta do modelo para o log de auditoria
            String safeRawOutput = modelArmorService.inspectOutput(rawThought);

            var node = objectMapper.createObjectNode();
            node.put("agentVersion",   "1.0");
            node.put("model",          "gemini-3.5-pro");
            node.put("parsedDecision",  decision.decision());
            node.put("riskScore",       decision.riskScore());
            node.put("reason",          decision.reason());
            node.put("rawModelOutput",  safeRawOutput);  // PII já censurado
            node.put("armorApplied",    true);
            node.put("analyzedAt",      LocalDateTime.now().toString());
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.warn("[TriageAgent] Falha ao serializar thoughtProcess — usando fallback string", e);
            return String.format(
                "{\"parsedDecision\":\"%s\",\"riskScore\":%s,\"reason\":\"%s\",\"rawModelOutput\":\"[SERIALIZATION_ERROR]\"}",
                decision.decision(), decision.riskScore(), decision.reason().replace("\"", "'")
            );
        }
    }
}
