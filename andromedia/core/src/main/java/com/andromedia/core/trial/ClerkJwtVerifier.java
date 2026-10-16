package com.andromedia.core.trial;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;

/** Verifies Clerk session JWTs against the configured JWKS endpoint. */
@Component
public class ClerkJwtVerifier {

  private static final Logger log = LoggerFactory.getLogger(ClerkJwtVerifier.class);

  private final TrialProperties properties;
  private volatile ConfigurableJWTProcessor<SecurityContext> processor;

  public ClerkJwtVerifier(TrialProperties properties) {
    this.properties = properties;
  }

  public boolean requireClerkJwt() {
    return properties.enabled() && properties.requireClerkJwt();
  }

  public Optional<String> extractBearer(ServerRequest request) {
    String header = request.headers().firstHeader("Authorization");
    if (header == null || header.isBlank()) {
      return Optional.empty();
    }
    String value = header.trim();
    if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
      String token = value.substring(7).trim();
      return token.isBlank() ? Optional.empty() : Optional.of(token);
    }
    return Optional.empty();
  }

  public String requireSubject(ServerRequest request) {
    String token =
        extractBearer(request)
            .orElseThrow(() -> new UnauthorizedTrialException("Bearer token required"));
    return verifySubject(token);
  }

  public String verifySubject(String token) {
    try {
      JWTClaimsSet claims = processor().process(token, null);
      if (properties.clerkIssuer() != null && !properties.clerkIssuer().isBlank()) {
        List<String> issuers = claims.getIssuer() == null ? List.of() : List.of(claims.getIssuer());
        if (!issuers.contains(properties.clerkIssuer().trim())) {
          throw new UnauthorizedTrialException("Invalid token issuer");
        }
      }
      String sub = claims.getSubject();
      if (sub == null || sub.isBlank()) {
        throw new UnauthorizedTrialException("Token missing subject");
      }
      return sub.trim();
    } catch (UnauthorizedTrialException ex) {
      throw ex;
    } catch (Exception ex) {
      log.warn("Clerk JWT verification failed: {}", ex.getMessage());
      throw new UnauthorizedTrialException("Invalid or expired token");
    }
  }

  private ConfigurableJWTProcessor<SecurityContext> processor() throws Exception {
    ConfigurableJWTProcessor<SecurityContext> existing = processor;
    if (existing != null) {
      return existing;
    }
    synchronized (this) {
      if (processor != null) {
        return processor;
      }
      String jwksUrl = properties.clerkJwksUrl();
      if (jwksUrl == null || jwksUrl.isBlank()) {
        throw new UnauthorizedTrialException("Clerk JWKS URL is not configured");
      }
      JWKSource<SecurityContext> keySource = new RemoteJWKSet<>(new URL(jwksUrl.trim()));
      ConfigurableJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
      JWSKeySelector<SecurityContext> keySelector =
          new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource);
      jwtProcessor.setJWSKeySelector(keySelector);
      processor = jwtProcessor;
      return processor;
    }
  }

  public static class UnauthorizedTrialException extends RuntimeException {
    public UnauthorizedTrialException(String message) {
      super(message);
    }
  }
}
