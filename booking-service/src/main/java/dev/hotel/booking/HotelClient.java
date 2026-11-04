package dev.hotel.booking;

import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.retry.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
class HotelClient {
  record Reserve(
      String requestId,
      Long roomId,
      boolean autoSelect,
      Long hotelId,
      LocalDate startDate,
      LocalDate endDate) {}

  record Reserved(Long roomId) {}

  record Release(String requestId) {}

  final RestClient client;
  final CircuitBreaker breaker =
      CircuitBreaker.of(
          "hotel-reserve",
          CircuitBreakerConfig.custom()
              .slidingWindowSize(6)
              .minimumNumberOfCalls(3)
              .failureRateThreshold(50)
              .waitDurationInOpenState(Duration.ofSeconds(10))
              .build());
  final Retry retry =
      Retry.of(
          "hotel-reserve",
          RetryConfig.custom()
              .maxAttempts(2)
              .waitDuration(Duration.ofMillis(50))
              .retryOnException(
                  e -> e instanceof RestClientException && !(e instanceof HttpClientErrorException))
              .build());

  HotelClient(
      @Value("${hotel.base-url}") String url, @Value("${hotel.timeout-ms:1500}") int timeout) {
    var f = new SimpleClientHttpRequestFactory();
    f.setConnectTimeout(timeout);
    f.setReadTimeout(timeout);
    client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(f)
            .requestInterceptor(
                (request, body, execution) -> {
                  String trace = org.slf4j.MDC.get("traceId");
                  if (trace != null) request.getHeaders().set("X-Request-Id", trace);
                  return execution.execute(request, body);
                })
            .build();
  }

  Long reserve(Booking b, String token) {
    return Retry.decorateSupplier(retry, () -> reserveOnce(b, token)).get();
  }

  Long reserveOnce(Booking b, String token) {
    breaker.acquirePermission();
    try {
      var r =
          client
              .post()
              .uri("/internal/rooms/reserve")
              .headers(h -> h.setBearerAuth(token))
              .body(
                  new Reserve(
                      b.requestId,
                      b.requestedRoomId,
                      b.autoSelect,
                      b.hotelId,
                      b.startDate,
                      b.endDate))
              .retrieve()
              .body(Reserved.class);
      if (r == null || r.roomId() == null) throw new IllegalStateException("Missing room response");
      breaker.onSuccess(0, TimeUnit.MILLISECONDS);
      return r.roomId();
    } catch (HttpClientErrorException business) {
      breaker.onSuccess(0, TimeUnit.MILLISECONDS);
      throw business;
    } catch (RuntimeException e) {
      breaker.onError(0, TimeUnit.MILLISECONDS, e);
      throw e;
    }
  }

  // Compensation must remain available when the reservation circuit is open.
  // Pending state is persisted; automatic retries still require an in-memory owner token.
  void release(Booking b, String token) {
    client
        .post()
        .uri("/internal/rooms/release")
        .headers(h -> h.setBearerAuth(token))
        .body(new Release(b.requestId))
        .retrieve()
        .toBodilessEntity();
  }
}
