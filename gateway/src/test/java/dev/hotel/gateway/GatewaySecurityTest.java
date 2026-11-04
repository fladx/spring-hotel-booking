package dev.hotel.gateway;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
@AutoConfigureWebTestClient
class GatewaySecurityTest {
  @Autowired WebTestClient client;

  @Test
  void businessRoutesRequireToken() {
    client.get().uri("/api/bookings").exchange().expectStatus().isUnauthorized();
  }

  @Test
  void internalReleaseNeverExposed() {
    client
        .mutateWith(mockJwt())
        .post()
        .uri("/internal/rooms/release")
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  @Test
  void healthPublicAndTraced() {
    client
        .get()
        .uri("/actuator/health")
        .header("X-Request-Id", "test-correlation")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("X-Request-Id", "test-correlation");
  }
}
