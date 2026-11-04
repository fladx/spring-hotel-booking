package dev.hotel.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.ErrorResponseException;

class ErrorAdviceTest {
  @Test
  void frameworkErrorsPreserveNonstandardStatusAndHeaders() {
    var request = new MockHttpServletRequest("GET", "/api/example");
    var error = new ErrorResponseException(HttpStatusCode.valueOf(599));
    error.getHeaders().set("Retry-After", "30");

    var response = new ErrorAdvice().unexpected(error, request);

    assertThat(response.getStatusCode().value()).isEqualTo(599);
    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");
    assertThat(response.getBody().status()).isEqualTo(599);
    assertThat(response.getBody().error()).isEqualTo("HTTP 599");
  }

  @Test
  void unexpectedFailuresKeepInternalServerErrorWithoutExposingExceptionDetails() {
    var request = new MockHttpServletRequest("GET", "/api/example");
    request.setAttribute("traceId", "test-error");

    var response =
        new ErrorAdvice()
            .unexpected(new IllegalStateException("private database details"), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().status()).isEqualTo(500);
    assertThat(response.getBody().message()).isEqualTo("Internal error");
    assertThat(response.getBody().path()).isEqualTo("/api/example");
    assertThat(response.getBody().traceId()).isEqualTo("test-error");
  }
}
