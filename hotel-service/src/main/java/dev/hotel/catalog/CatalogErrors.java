package dev.hotel.catalog;

import dev.hotel.common.ErrorAdvice;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
@Order(-1)
class CatalogErrors {
  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ErrorAdvice.ErrorDTO> conflict(
      DataIntegrityViolationException e, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            ErrorAdvice.body(
                HttpStatus.CONFLICT,
                "The record conflicts with existing data or database constraints",
                request.getRequestURI(),
                String.valueOf(request.getAttribute("traceId"))));
  }
}
