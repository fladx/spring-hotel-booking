package dev.hotel.booking;

import dev.hotel.common.JwtSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
class SecurityConfig {
  @Bean
  JwtSupport jwtSupport(@Value("${app.jwt.secret}") String secret) {
    return new JwtSupport(secret);
  }

  @Bean
  JwtEncoder encoder(JwtSupport support) {
    return support.encoder();
  }

  @Bean
  JwtDecoder decoder(JwtSupport support) {
    return support.decoder();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http, com.fasterxml.jackson.databind.ObjectMapper mapper) throws Exception {
    var roles = new JwtGrantedAuthoritiesConverter();
    roles.setAuthoritiesClaimName("roles");
    roles.setAuthorityPrefix("ROLE_");
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(roles);
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/auth/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers("/api/users/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) ->
                            error(
                                mapper, req, res, org.springframework.http.HttpStatus.UNAUTHORIZED))
                    .accessDeniedHandler(
                        (req, res, ex) ->
                            error(mapper, req, res, org.springframework.http.HttpStatus.FORBIDDEN)))
        .oauth2ResourceServer(
            o ->
                o.authenticationEntryPoint(
                        (req, res, ex) ->
                            error(
                                mapper, req, res, org.springframework.http.HttpStatus.UNAUTHORIZED))
                    .accessDeniedHandler(
                        (req, res, ex) ->
                            error(mapper, req, res, org.springframework.http.HttpStatus.FORBIDDEN))
                    .jwt(j -> j.jwtAuthenticationConverter(converter)))
        .build();
  }

  private static void error(
      com.fasterxml.jackson.databind.ObjectMapper mapper,
      jakarta.servlet.http.HttpServletRequest req,
      jakarta.servlet.http.HttpServletResponse res,
      org.springframework.http.HttpStatus status)
      throws java.io.IOException {
    res.setStatus(status.value());
    res.setContentType("application/json");
    if (status == org.springframework.http.HttpStatus.UNAUTHORIZED)
      res.setHeader("WWW-Authenticate", "Bearer");
    mapper.writeValue(
        res.getOutputStream(),
        dev.hotel.common.ErrorAdvice.body(
            status,
            status == org.springframework.http.HttpStatus.UNAUTHORIZED
                ? "Authentication required"
                : "Access denied",
            req.getRequestURI(),
            String.valueOf(req.getAttribute("traceId"))));
  }
}
