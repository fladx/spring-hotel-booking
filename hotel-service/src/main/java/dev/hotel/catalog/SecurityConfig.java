package dev.hotel.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hotel.common.ErrorAdvice;
import dev.hotel.common.JwtSupport;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
class SecurityConfig {
  @Bean
  JwtDecoder decoder(@Value("${app.jwt.secret}") String secret) {
    return new JwtSupport(secret).decoder();
  }

  private void error(
      ObjectMapper mapper, HttpServletRequest req, HttpServletResponse res, HttpStatus status)
      throws IOException {
    res.setStatus(status.value());
    res.setContentType("application/json");
    mapper.writeValue(
        res.getOutputStream(),
        ErrorAdvice.body(
            status,
            status == HttpStatus.UNAUTHORIZED ? "Valid bearer token required" : "Access denied",
            req.getRequestURI(),
            String.valueOf(req.getAttribute("traceId"))));
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper) throws Exception {
    var roles = new JwtGrantedAuthoritiesConverter();
    roles.setAuthoritiesClaimName("roles");
    roles.setAuthorityPrefix("ROLE_");
    var jwt = new JwtAuthenticationConverter();
    jwt.setJwtGrantedAuthoritiesConverter(roles);
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) -> error(mapper, req, res, HttpStatus.UNAUTHORIZED))
                    .accessDeniedHandler(
                        (req, res, ex) -> error(mapper, req, res, HttpStatus.FORBIDDEN)))
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> j.jwtAuthenticationConverter(jwt))
                    .authenticationEntryPoint(
                        (req, res, ex) -> error(mapper, req, res, HttpStatus.UNAUTHORIZED))
                    .accessDeniedHandler(
                        (req, res, ex) -> error(mapper, req, res, HttpStatus.FORBIDDEN)))
        .build();
  }
}
