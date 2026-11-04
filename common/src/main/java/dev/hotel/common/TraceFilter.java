package dev.hotel.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String id = req.getHeader("X-Request-Id");
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) id = UUID.randomUUID().toString();
    try (var context = MDC.putCloseable("traceId", id)) {
      res.setHeader("X-Request-Id", id);
      req.setAttribute("traceId", id);
      chain.doFilter(req, res);
    }
  }
}
