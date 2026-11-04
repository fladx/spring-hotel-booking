package dev.hotel.common;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

class JwtSupportTest {
  private final JwtSupport support =
      new JwtSupport("demo-only-not-for-production-hotel-booking-secret-0000000000000000");

  private String token(String issuer, Instant expiry) {
    return support
        .encoder()
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                    .issuer(issuer)
                    .subject("1")
                    .issuedAt(Instant.now().minusSeconds(3600))
                    .expiresAt(expiry)
                    .claim("roles", List.of("USER"))
                    .build()))
        .getTokenValue();
  }

  @Test
  void roundtrip() {
    assertEquals(
        "1",
        support
            .decoder()
            .decode(token("hotel-booking", Instant.now().plusSeconds(3600)))
            .getSubject());
  }

  @Test
  void expiredRejected() {
    assertThrows(
        JwtException.class,
        () -> support.decoder().decode(token("hotel-booking", Instant.now().minusSeconds(120))));
  }

  @Test
  void wrongIssuerRejected() {
    assertThrows(
        JwtException.class,
        () -> support.decoder().decode(token("other", Instant.now().plusSeconds(3600))));
  }

  @Test
  void wrongKeyRejected() {
    var other =
        new JwtSupport("different-not-for-production-hotel-booking-secret-0000000000000000");
    assertThrows(
        JwtException.class,
        () -> other.decoder().decode(token("hotel-booking", Instant.now().plusSeconds(3600))));
  }
}
