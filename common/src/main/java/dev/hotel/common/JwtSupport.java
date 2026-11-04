package dev.hotel.common;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

/** Shared algorithm/issuer policy. Keys in demo configuration are NOT production credentials. */
public final class JwtSupport {
  private final SecretKeySpec key;

  public JwtSupport(String secret) {
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
      throw new IllegalArgumentException("JWT_SECRET must contain at least 32 bytes");
    key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  public JwtEncoder encoder() {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }

  public JwtDecoder decoder() {
    var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("hotel-booking"));
    return decoder;
  }
}
