package com.andromedia.core.trial;

import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * When trial mode is on, data-plane routes must use {@code service == X-Trial-Id} so one trial
 * cannot read or write another trial's index. When Clerk JWT is required, the trial id must match
 * the authenticated Clerk user.
 */
@Component
public class TrialAccessGuard {

  public static final String TRIAL_ID_HEADER = "X-Trial-Id";

  private final TrialService trialService;
  private final ClerkJwtVerifier clerkJwtVerifier;

  public TrialAccessGuard(TrialService trialService, ClerkJwtVerifier clerkJwtVerifier) {
    this.trialService = trialService;
    this.clerkJwtVerifier = clerkJwtVerifier;
  }

  public boolean trialEnabled() {
    return trialService.enabled();
  }

  /**
   * @return empty if access is allowed; otherwise a ready 4xx response
   */
  public Optional<Mono<ServerResponse>> rejectUnlessServiceMatchesTrial(
      ServerRequest request, String service) {
    if (!trialService.enabled()) {
      return Optional.empty();
    }

    String trialId = request.headers().firstHeader(TRIAL_ID_HEADER);
    if (trialId == null || trialId.isBlank()) {
      return Optional.of(
          ServerResponse.badRequest()
              .bodyValue(Map.of("error", "X-Trial-Id header is required in trial mode")));
    }

    String trimmedTrial = trialId.trim();
    String trimmedService = service == null ? "" : service.trim();

    if (trimmedService.isBlank()) {
      return Optional.of(ServerResponse.badRequest().bodyValue(Map.of("error", "service is required")));
    }

    if ("local".equalsIgnoreCase(trimmedService)) {
      return Optional.of(
          ServerResponse.status(HttpStatus.FORBIDDEN)
              .bodyValue(
                  Map.of(
                      "error",
                      "Trial mode rejects service=local; use your trial id as the service name")));
    }

    if (!trimmedService.equals(trimmedTrial)) {
      return Optional.of(
          ServerResponse.status(HttpStatus.FORBIDDEN)
              .bodyValue(
                  Map.of(
                      "error",
                      "service must match X-Trial-Id in trial mode")));
    }

    Optional<Mono<ServerResponse>> authDenied = rejectUnlessClerkOwnsTrial(request, trimmedTrial);
    if (authDenied.isPresent()) {
      return authDenied;
    }

    try {
      trialService.status(trimmedTrial);
    } catch (IllegalArgumentException ex) {
      return Optional.of(
          ServerResponse.badRequest()
              .bodyValue(Map.of("error", "Unknown or expired trial session")));
    } catch (TrialService.TrialNotEnabledException ex) {
      return Optional.empty();
    }

    return Optional.empty();
  }

  /** Validates Clerk JWT owns this trial id when required. */
  public Optional<Mono<ServerResponse>> rejectUnlessClerkOwnsTrial(
      ServerRequest request, String trialId) {
    if (!clerkJwtVerifier.requireClerkJwt()) {
      return Optional.empty();
    }
    try {
      String subject = clerkJwtVerifier.requireSubject(request);
      if (!TrialIdMapper.matches(trialId, subject)) {
        return Optional.of(
            ServerResponse.status(HttpStatus.FORBIDDEN)
                .bodyValue(Map.of("error", "trialId does not belong to the authenticated user")));
      }
      return Optional.empty();
    } catch (ClerkJwtVerifier.UnauthorizedTrialException ex) {
      return Optional.of(
          ServerResponse.status(HttpStatus.UNAUTHORIZED)
              .bodyValue(Map.of("error", ex.getMessage())));
    }
  }
}
