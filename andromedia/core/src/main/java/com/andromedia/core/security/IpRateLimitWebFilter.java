package com.andromedia.core.security;

import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * IP rate limit for {@code /api/**}. Health checks and index job-status GETs are exempt (UI polls
 * every ~1.5s). Blank client IPs share an {@code unknown} bucket so limits still apply.
 *
 * <p>CORS alone does not stop curl/scripts; this filter is the primary cost-abuse control.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class IpRateLimitWebFilter implements WebFilter {

  private static final Set<String> INDEX_JOB_SOURCES =
      Set.of("history", "slack", "notion", "jira", "linear", "zoho");

  private final RateLimitProperties properties;
  private final IpRateLimiter limiter;

  public IpRateLimitWebFilter(RateLimitProperties properties) {
    this.properties = properties;
    this.limiter = new IpRateLimiter(properties.requestsPerMinute());
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    if (!properties.enabled()) {
      return chain.filter(exchange);
    }

    String path = exchange.getRequest().getURI().getPath();
    if (path == null || !path.startsWith("/api/")) {
      return chain.filter(exchange);
    }
    if (HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
      return chain.filter(exchange);
    }
    if ("/api/v1/health".equals(path) || "/api/v1/trial/config".equals(path)) {
      return chain.filter(exchange);
    }
    if (HttpMethod.GET.equals(exchange.getRequest().getMethod()) && isIndexJobStatusPath(path)) {
      return chain.filter(exchange);
    }

    String clientIp = resolveClientIp(exchange);
    if (!limiter.allowRequest(clientIp)) {
      exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
      exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
      byte[] body = "{\"error\":\"rate limit exceeded\"}".getBytes();
      return exchange
          .getResponse()
          .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    return chain.filter(exchange);
  }

  /**
   * Configure-modal job pollers: {@code GET /api/v1/index/{jobId}} and {@code GET
   * /api/v1/index/{source}/{jobId}}. Overview under {@code /api/v1/index/services/...} is not
   * exempt.
   */
  static boolean isIndexJobStatusPath(String path) {
    if (path == null || !path.startsWith("/api/v1/index/")) {
      return false;
    }
    if (path.startsWith("/api/v1/index/services/")) {
      return false;
    }
    String rest = path.substring("/api/v1/index/".length());
    if (rest.isBlank() || rest.endsWith("/")) {
      return false;
    }
    String[] parts = rest.split("/");
    if (parts.length == 1) {
      // GET /api/v1/index/{jobId} — reserved source names alone are not job polls.
      return !INDEX_JOB_SOURCES.contains(parts[0]) && !"services".equals(parts[0]);
    }
    if (parts.length == 2) {
      return INDEX_JOB_SOURCES.contains(parts[0]) && !parts[1].isBlank();
    }
    return false;
  }

  static String resolveClientIp(ServerWebExchange exchange) {
    String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    if (exchange.getRequest().getRemoteAddress() != null
        && exchange.getRequest().getRemoteAddress().getAddress() != null) {
      return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }
    return "unknown";
  }
}
