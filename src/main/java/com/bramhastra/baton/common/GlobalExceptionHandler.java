package com.bramhastra.baton.common;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e) {
        ResponseEntity.BodyBuilder res = ResponseEntity.status(e.code().status());
        if (e.details() instanceof Map<?, ?> d && d.get("retryAfterSeconds") instanceof Number n) {
            res.header("Retry-After", String.valueOf(n.longValue()));
        }
        return res.body(new ApiError(e.code().name(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return body(ErrorCode.VALIDATION_FAILED, "Request validation failed", fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return body(ErrorCode.VALIDATION_FAILED, "Malformed request body", null);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> stale(ObjectOptimisticLockingFailureException e) {
        return body(ErrorCode.CLAIM_STALE, "This item was changed by someone else; reload and retry", null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e) {
        return body(ErrorCode.FILE_TOO_LARGE, "File exceeds 25 MB", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException e) {
        return body(ErrorCode.UNSUPPORTED_TYPE, "Unsupported content type", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noResource(NoResourceFoundException e) {
        return body(ErrorCode.NOT_FOUND, "Not found", null);
    }

    /**
     * A browser closed a live stream (the team room, job progress) while the server was writing to it. Nobody is left
     * to answer, and it is not an error: there is nothing to log and nothing to send.
     */
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    void clientGone(org.springframework.web.context.request.async.AsyncRequestNotUsableException e) {
        // intentionally empty
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest request) {
        // Never log the message or stack trace text: they can contain company content. The ref lets a user
        // report the error and lets us find this line.
        String ref = Long.toHexString(Double.doubleToLongBits(Math.random())).substring(0, 8);
        log.error("Unhandled error ref={} {} {}: {}", ref, request.getMethod(), request.getRequestURI(), SafeLog.describe(e));
        return body(ErrorCode.INTERNAL_ERROR, "Unexpected error (ref " + ref + ")", Map.of("ref", ref));
    }

    private static ResponseEntity<ApiError> body(ErrorCode code, String message, Object details) {
        return ResponseEntity.status(code.status()).body(new ApiError(code.name(), message, details));
    }
}
