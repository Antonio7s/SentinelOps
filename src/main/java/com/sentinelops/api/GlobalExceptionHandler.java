package com.sentinelops.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Handler global de exceções para a API REST do SentinelOps.
 *
 * <p>Garante que todos os erros retornem no formato RFC-7807 (Problem Details)
 * ao invés das respostas de erro padrão do Spring Boot, que podem expor
 * stack traces e detalhes internos indesejados.
 *
 * <p>Tratamentos implementados:
 * <ul>
 *   <li>{@code 400} — Erros de validação Bean Validation (@Valid)</li>
 *   <li>{@code 4xx/5xx} — ResponseStatusException do controller</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Trata erros de validação de Bean Validation (@Valid).
     *
     * <p>Retorna HTTP 400 com detalhes de cada campo inválido no formato:
     * <pre>
     * {
     *   "type":   "https://sentinelops.io/errors/validation-failed",
     *   "title":  "Validation Failed",
     *   "status": 400,
     *   "detail": "Um ou mais campos são inválidos.",
     *   "fields": {
     *     "amount":    "amount deve ser maior que zero.",
     *     "accountId": "accountId é obrigatório e não pode ser vazio."
     *   }
     * }
     * </pre>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Campo inválido.",
                        // Em caso de múltiplos erros no mesmo campo, mantém o último
                        (existing, replacement) -> replacement));

        log.warn("[GlobalExceptionHandler] Validation failed | errors={}", fieldErrors);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Um ou mais campos são inválidos.");

        problem.setType(URI.create("https://sentinelops.io/errors/validation-failed"));
        problem.setTitle("Validation Failed");
        problem.setProperty("fields", fieldErrors);
        problem.setProperty("timestamp", Instant.now().toString());

        return problem;
    }

    /**
     * Trata {@link ResponseStatusException} lançadas pelos controllers (404, 409 etc).
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException ex) {
        log.warn("[GlobalExceptionHandler] ResponseStatusException | status={} | reason={}",
                ex.getStatusCode(), ex.getReason());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                ex.getStatusCode(),
                ex.getReason() != null ? ex.getReason() : ex.getMessage());

        problem.setType(URI.create("https://sentinelops.io/errors/api-error"));
        problem.setProperty("timestamp", Instant.now().toString());

        return problem;
    }
}
