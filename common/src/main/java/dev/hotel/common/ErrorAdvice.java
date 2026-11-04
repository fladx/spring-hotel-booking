package dev.hotel.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ErrorAdvice {
  public record ErrorDTO(
      Instant timestamp, int status, String error, String message, String path, String traceId) {}

  public static ErrorDTO body(HttpStatusCode status, String message, String path, String traceId) {
    HttpStatus standard = HttpStatus.resolve(status.value());
    String reason = standard == null ? "HTTP " + status.value() : standard.getReasonPhrase();
    return new ErrorDTO(Instant.now(), status.value(), reason, message, path, traceId);
  }

  private ResponseEntity<ErrorDTO> response(
      HttpStatus status, String message, HttpServletRequest req) {
    return ResponseEntity.status(status)
        .body(
            body(
                status, message, req.getRequestURI(), String.valueOf(req.getAttribute("traceId"))));
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ErrorDTO> api(ApiException e, HttpServletRequest r) {
    return response(e.status(), e.getMessage(), r);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    ConstraintViolationException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<ErrorDTO> validation(Exception e, HttpServletRequest r) {
    return response(HttpStatus.BAD_REQUEST, "Invalid request: check fields and date format", r);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ErrorDTO> denied(AccessDeniedException e, HttpServletRequest r) {
    return response(HttpStatus.FORBIDDEN, "Access denied", r);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorDTO> unexpected(Exception e, HttpServletRequest r) {
    if (e instanceof ErrorResponse error) {
      return ResponseEntity.status(error.getStatusCode())
          .headers(error.getHeaders())
          .body(
              body(
                  error.getStatusCode(),
                  error.getBody().getTitle(),
                  r.getRequestURI(),
                  String.valueOf(r.getAttribute("traceId"))));
    }
    LoggerFactory.getLogger(ErrorAdvice.class)
        .error("event=request_failed type={}", e.getClass().getSimpleName(), e);
    return response(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", r);
  }
}
