package com.vms.api.common.error;

import com.vms.api.common.web.RequestIdFilter;
import com.vms.api.video.domain.IllegalStateTransitionException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every exception to an RFC 7807 problem response with a stable {@code code}. Stack traces and
 * internal messages are logged, never returned.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
    if (ex.code().status().is5xxServerError()) {
      log.error("Request failed: {}", ex.code(), ex);
    }
    return problem(ex.code(), ex.getMessage(), List.of());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
    List<Map<String, String>> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(
                e ->
                    Map.of(
                        "field",
                        e.getField(),
                        "message",
                        e.getDefaultMessage() == null ? "is invalid" : e.getDefaultMessage()))
            .toList();
    return problem(
        ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), errors);
  }

  @ExceptionHandler({
    ConstraintViolationException.class,
    MethodArgumentTypeMismatchException.class,
    MissingRequestHeaderException.class
  })
  ResponseEntity<ProblemDetail> handleBadParameter(Exception ex) {
    return problem(
        ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), List.of());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex) {
    return problem(
        ErrorCode.MALFORMED_REQUEST, ErrorCode.MALFORMED_REQUEST.defaultMessage(), List.of());
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ProblemDetail> handleMediaType(HttpMediaTypeNotSupportedException ex) {
    return problem(
        ErrorCode.UNSUPPORTED_MEDIA_TYPE,
        ErrorCode.UNSUPPORTED_MEDIA_TYPE.defaultMessage(),
        List.of());
  }

  @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
  ResponseEntity<ProblemDetail> handleNotFound(Exception ex) {
    return problem(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage(), List.of());
  }

  @ExceptionHandler(IllegalStateTransitionException.class)
  ResponseEntity<ProblemDetail> handleTransition(IllegalStateTransitionException ex) {
    return problem(ErrorCode.INVALID_STATE, ErrorCode.INVALID_STATE.defaultMessage(), List.of());
  }

  @ExceptionHandler({
    DataIntegrityViolationException.class,
    OptimisticLockingFailureException.class
  })
  ResponseEntity<ProblemDetail> handleConflict(Exception ex) {
    log.warn("Concurrent modification: {}", ex.getClass().getSimpleName());
    return problem(
        ErrorCode.DUPLICATE_REQUEST, ErrorCode.DUPLICATE_REQUEST.defaultMessage(), List.of());
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
    return problem(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage(), List.of());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
    log.error("Unhandled exception", ex);
    return problem(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), List.of());
  }

  public static ProblemDetail toProblem(ErrorCode code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
    problem.setType(URI.create("https://vms.example/errors/" + code.name().toLowerCase()));
    problem.setTitle(code.status().getReasonPhrase());
    problem.setProperty("code", code.name());
    String requestId = MDC.get(RequestIdFilter.MDC_KEY);
    if (requestId != null) {
      problem.setProperty("requestId", requestId);
    }
    return problem;
  }

  private static ResponseEntity<ProblemDetail> problem(
      ErrorCode code, String detail, List<Map<String, String>> errors) {
    ProblemDetail problem = toProblem(code, detail);
    if (!errors.isEmpty()) {
      problem.setProperty("errors", errors);
    }
    return ResponseEntity.status(code.status()).body(problem);
  }
}
