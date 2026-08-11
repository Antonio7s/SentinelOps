package com.sentinelops.api;

import com.sentinelops.core.Transaction;
import com.sentinelops.core.TransactionIngestionService;
import com.sentinelops.core.TransactionRepository;
import com.sentinelops.core.TransactionStatus;
import com.sentinelops.core.dto.TransactionRequestDto;
import com.sentinelops.core.dto.TransactionResponseDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Controller REST para ingestão e consulta de transações financeiras.
 *
 * <p>Expõe os endpoints públicos do SentinelOps para integração com sistemas externos.
 * Segue as convenções REST com versionamento de API via prefixo {@code /api/v1/}.
 *
 * <h2>Endpoints</h2>
 * <table border="1" cellpadding="4">
 *   <tr><th>Método</th><th>Path</th><th>Status</th><th>Descrição</th></tr>
 *   <tr><td>POST</td><td>/api/v1/transactions</td><td>202</td><td>Ingere nova transação</td></tr>
 *   <tr><td>GET</td><td>/api/v1/transactions/{id}</td><td>200/404</td><td>Consulta status</td></tr>
 *   <tr><td>GET</td><td>/api/v1/transactions?status=PENDING</td><td>200</td><td>Lista por status</td></tr>
 * </table>
 *
 * <h2>Por que HTTP 202 (Accepted)?</h2>
 * <p>A ingestão é assíncrona por natureza: a transação é persistida como PENDING
 * e os agentes de IA a processarão em segundo plano. HTTP 202 sinaliza ao cliente
 * que a requisição foi aceita mas o processamento ainda não foi concluído,
 * diferenciando do 201 (Created) que implicaria finalização imediata.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class IngestionController {

    private final TransactionIngestionService ingestionService;
    private final TransactionRepository      transactionRepository;

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/v1/transactions — Ingestão
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ingere uma nova transação financeira no pipeline de análise do SentinelOps.
     *
     * <p>A transação é imediatamente persistida com status {@link TransactionStatus#PENDING}
     * e será processada pelos agentes de IA de forma assíncrona.
     *
     * <p><b>Exemplo de request:</b>
     * <pre>
     * POST /api/v1/transactions
     * Content-Type: application/json
     *
     * {
     *   "accountId":        "ACC-00123456",
     *   "amount":           1850.75,
     *   "merchantCategory": "ELECTRONICS",
     *   "timestamp":        "2026-08-10T01:15:00"
     * }
     * </pre>
     *
     * @param request DTO validado com os dados da transação
     * @return {@code 202 Accepted} com o UUID gerado e o link de status
     */
    @PostMapping
    public ResponseEntity<TransactionResponseDto> ingestTransaction(
            @Valid @RequestBody TransactionRequestDto request) {

        log.info("[IngestionController] Requisição recebida | accountId={} | amount={} | category={}",
                request.accountId(), request.amount(), request.merchantCategory());

        TransactionResponseDto response = ingestionService.ingest(request);

        log.info("[IngestionController] Ingestão concluída | transactionId={} | statusUrl={}",
                response.transactionId(), response.statusUrl());

        // HTTP 202 — Accepted: persistido, aguardando processamento dos agentes
        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(response);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/transactions/{id} — Consulta de Status
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Consulta o status atual de uma transação pelo seu UUID.
     *
     * <p>Permite que clientes façam polling para verificar se os agentes
     * concluíram a análise e qual foi a decisão final.
     *
     * @param id UUID da transação (path variable)
     * @return {@code 200 OK} com o DTO da transação, ou {@code 404} se não encontrada
     */
    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponseDto> getTransactionStatus(
            @PathVariable UUID id) {

        log.debug("[IngestionController] Consulta de status | id={}", id);

        Transaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("[IngestionController] Transação não encontrada | id={}", id);
                    return new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Transaction not found: " + id);
                });

        return ResponseEntity.ok(TransactionResponseDto.from(transaction));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/transactions?status=PENDING — Listagem por Status
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lista transações filtradas por status.
     *
     * <p>Útil para dashboards operacionais:
     * <ul>
     *   <li>{@code ?status=PENDING} — fila aguardando processamento</li>
     *   <li>{@code ?status=MANUAL_REVIEW} — casos que exigem atenção humana</li>
     *   <li>{@code ?status=BLOCKED} — transações bloqueadas por suspeita</li>
     * </ul>
     *
     * @param status filtro de status (opcional; sem filtro retorna todas)
     * @return {@code 200 OK} com lista de transações
     */
    @GetMapping
    public ResponseEntity<?> listTransactions(
            @RequestParam(required = false) TransactionStatus status) {

        if (status != null) {
            log.debug("[IngestionController] Listagem por status={}", status);
            var result = transactionRepository.findByStatusOrderByTimestampDesc(status)
                    .stream()
                    .map(TransactionResponseDto::from)
                    .toList();
            return ResponseEntity.ok(result);
        }

        log.debug("[IngestionController] Listagem completa (sem filtro)");
        var result = transactionRepository.findAll()
                .stream()
                .map(TransactionResponseDto::from)
                .toList();
        return ResponseEntity.ok(result);
    }
}
