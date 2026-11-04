package dev.hotel.booking;

import dev.hotel.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
class AuthController {
  record Credentials(
      @NotBlank @Size(min = 3, max = 80) String username,
      @NotBlank @Size(min = 8, max = 72) String password) {}

  record Token(String accessToken, String tokenType, long expiresIn) {}

  final UserRepository users;
  final PasswordEncoder passwords;
  final JwtEncoder encoder;

  AuthController(UserRepository u, PasswordEncoder p, JwtEncoder e) {
    users = u;
    passwords = p;
    encoder = e;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  Token register(@Valid @RequestBody Credentials c) {
    validatePassword(c.password());
    try {
      return token(
          users.saveAndFlush(
              new UserAccount(
                  c.username().trim().toLowerCase(Locale.ROOT),
                  passwords.encode(c.password()),
                  "USER")));
    } catch (DataIntegrityViolationException ex) {
      throw new ApiException(HttpStatus.CONFLICT, "Username already exists");
    }
  }

  @PostMapping("/login")
  Token login(@Valid @RequestBody Credentials c) {
    var u =
        users
            .findByUsername(c.username().trim().toLowerCase(Locale.ROOT))
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
    if (!passwords.matches(c.password(), u.passwordHash))
      throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    return token(u);
  }

  static void validatePassword(String password) {
    if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw new ApiException(HttpStatus.BAD_REQUEST, "Password must not exceed 72 UTF-8 bytes");
  }

  Token token(UserAccount u) {
    var now = Instant.now();
    var claims =
        JwtClaimsSet.builder()
            .issuer("hotel-booking")
            .subject(u.id.toString())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(3600))
            .claim("roles", List.of(u.role))
            .build();
    return new Token(
        encoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue(),
        "Bearer",
        3600);
  }
}
