package dev.hotel.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.*;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

@Configuration
public class GatewayConfig {
  @Bean
  RouteLocator routes(RouteLocatorBuilder b) {
    return b.routes()
        .route(
            "booking",
            r ->
                r.path("/api/auth/**", "/api/users/**", "/api/bookings/**")
                    .uri("lb://booking-service"))
        .route("hotel", r -> r.path("/api/hotels/**", "/api/rooms/**").uri("lb://hotel-service"))
        .build();
  }

  @Bean
  ReactiveJwtDecoder jwtDecoder(@Value("${app.jwt.secret}") String secret) {
    if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
      throw new IllegalArgumentException("JWT_SECRET too short");
    var decoder =
        NimbusReactiveJwtDecoder.withSecretKey(
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("hotel-booking"));
    return decoder;
  }

  @Bean
  SecurityWebFilterChain security(ServerHttpSecurity http, ObjectMapper mapper) {
    return http.exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (exchange, error) -> error(exchange, mapper, HttpStatus.UNAUTHORIZED))
                    .accessDeniedHandler(
                        (exchange, error) -> error(exchange, mapper, HttpStatus.FORBIDDEN)))
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .authorizeExchange(
            a ->
                a.pathMatchers("/api/auth/**", "/actuator/health")
                    .permitAll()
                    .pathMatchers("/internal/**")
                    .denyAll()
                    .anyExchange()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> {})
                    .authenticationEntryPoint(
                        (exchange, error) -> error(exchange, mapper, HttpStatus.UNAUTHORIZED))
                    .accessDeniedHandler(
                        (exchange, error) -> error(exchange, mapper, HttpStatus.FORBIDDEN)))
        .build();
  }

  private Mono<Void> error(ServerWebExchange exchange, ObjectMapper mapper, HttpStatus status) {
    exchange.getResponse().setStatusCode(status);
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
    try {
      byte[] bytes =
          mapper.writeValueAsBytes(
              Map.of(
                  "timestamp",
                  Instant.now().toString(),
                  "status",
                  status.value(),
                  "error",
                  status.getReasonPhrase(),
                  "message",
                  status.getReasonPhrase(),
                  "path",
                  exchange.getRequest().getPath().value(),
                  "traceId",
                  String.valueOf(exchange.getResponse().getHeaders().getFirst("X-Request-Id"))));
      return exchange
          .getResponse()
          .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    } catch (Exception e) {
      return exchange.getResponse().setComplete();
    }
  }

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  WebFilter trace() {
    return (exchange, chain) -> {
      String incoming = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
      String trace =
          incoming != null && incoming.matches("[A-Za-z0-9_-]{1,64}")
              ? incoming
              : UUID.randomUUID().toString();
      var decorated =
          exchange
              .mutate()
              .request(
                  exchange.getRequest().mutate().headers(h -> h.set("X-Request-Id", trace)).build())
              .build();
      decorated.getResponse().getHeaders().set("X-Request-Id", trace);
      return chain
          .filter(decorated)
          .doFinally(
              signal ->
                  LoggerFactory.getLogger(GatewayConfig.class)
                      .info(
                          "event=gateway_request traceId={} method={} path={} status={}",
                          trace,
                          decorated.getRequest().getMethod(),
                          decorated.getRequest().getPath(),
                          decorated.getResponse().getStatusCode()));
    };
  }
}
