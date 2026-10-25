package com.wallet.wallet_ledger.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/** Turns every error into the same JSON shape: {code, message, request_id}. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException e, HttpServletRequest request) {
        return build(e.getStatus(), e.getCode(), e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException e, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "MISSING_HEADER",
                "Required header '" + e.getHeaderName() + "' is missing", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                "Invalid value for '" + e.getName() + "'", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Request body is missing or not valid JSON", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleConstraint(DataIntegrityViolationException e, HttpServletRequest request) {
        log.warn("Constraint violation", e);
        return build(HttpStatus.CONFLICT, "CONSTRAINT_VIOLATION",
                "The request conflicts with existing data", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNotFound(NoResourceFoundException e, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "No endpoint " + request.getRequestURI(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unexpected error", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong", request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, HttpServletRequest request) {
        String requestId = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return ResponseEntity.status(status).body(new ApiError(code, message, requestId));
    }
}