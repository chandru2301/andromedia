package com.andromedia.core.credentials;

import com.andromedia.core.trial.ClerkJwtVerifier;
import com.andromedia.core.trial.TrialAccessGuard;
import com.andromedia.core.trial.TrialIdMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** HTTP-facing credential vault operations (list / upsert / delete). */
@Service
public class CredentialApiService {

  private final UserCredentialStore store;
  private final TrialAccessGuard trialAccessGuard;
  private final ClerkJwtVerifier clerkJwtVerifier;

  public CredentialApiService(
      UserCredentialStore store,
      TrialAccessGuard trialAccessGuard,
      ClerkJwtVerifier clerkJwtVerifier) {
    this.store = store;
    this.trialAccessGuard = trialAccessGuard;
    this.clerkJwtVerifier = clerkJwtVerifier;
  }

  public Mono<ServerResponse> list(ServerRequest request) {
    if (!store.enabled()) {
      return ServerResponse.ok()
          .bodyValue(Map.of("enabled", false, "credentials", java.util.List.of()));
    }
    Optional<String> trialId = resolveTrialId(request);
    if (trialId.isEmpty()) {
      return ServerResponse.badRequest()
          .bodyValue(Map.of("error", "X-Trial-Id (or service) is required"));
    }
    Optional<Mono<ServerResponse>> denied =
        trialAccessGuard.rejectUnlessServiceMatchesTrial(request, trialId.get());
    if (denied.isPresent()) {
      return denied.get();
    }
    Optional<Mono<ServerResponse>> authDenied = rejectUnlessOwnsTrial(request, trialId.get());
    if (authDenied.isPresent()) {
      return authDenied.get();
    }
    return Mono.fromCallable(
            () -> {
              touch(request, trialId.get());
              Map<String, Object> body = new LinkedHashMap<>();
              body.put("enabled", true);
              body.put(
                  "credentials",
                  store.listPublic(trialId.get()).stream().map(this::toMap).toList());
              return body;
            })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(body -> ServerResponse.ok().bodyValue(body))
        .onErrorResume(this::mapError);
  }

  public Mono<ServerResponse> upsert(ServerRequest request) {
    if (!store.enabled()) {
      return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
          .bodyValue(
              Map.of(
                  "error",
                  "PostgreSQL credential vault is disabled. Set ANDROMEDIA_CREDENTIALS_POSTGRES_ENABLED=true"));
    }
    String provider = request.pathVariable("provider");
    Optional<String> trialId = resolveTrialId(request);
    if (trialId.isEmpty()) {
      return ServerResponse.badRequest()
          .bodyValue(Map.of("error", "X-Trial-Id (or service) is required"));
    }
    Optional<Mono<ServerResponse>> denied =
        trialAccessGuard.rejectUnlessServiceMatchesTrial(request, trialId.get());
    if (denied.isPresent()) {
      return denied.get();
    }
    Optional<Mono<ServerResponse>> authDenied = rejectUnlessOwnsTrial(request, trialId.get());
    if (authDenied.isPresent()) {
      return authDenied.get();
    }
    return request
        .bodyToMono(CredentialUpsertRequest.class)
        .flatMap(
            body ->
                Mono.fromCallable(
                        () -> {
                          touch(request, trialId.get());
                          return upsertBody(trialId.get(), provider, body);
                        })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(view -> ServerResponse.ok().bodyValue(toMap(view))))
        .switchIfEmpty(
            ServerResponse.badRequest().bodyValue(Map.of("error", "Request body is required")))
        .onErrorResume(this::mapError);
  }

  public Mono<ServerResponse> delete(ServerRequest request) {
    if (!store.enabled()) {
      return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
          .bodyValue(
              Map.of(
                  "error",
                  "PostgreSQL credential vault is disabled. Set ANDROMEDIA_CREDENTIALS_POSTGRES_ENABLED=true"));
    }
    String provider = request.pathVariable("provider");
    Optional<String> trialId = resolveTrialId(request);
    if (trialId.isEmpty()) {
      return ServerResponse.badRequest()
          .bodyValue(Map.of("error", "X-Trial-Id (or service) is required"));
    }
    Optional<Mono<ServerResponse>> denied =
        trialAccessGuard.rejectUnlessServiceMatchesTrial(request, trialId.get());
    if (denied.isPresent()) {
      return denied.get();
    }
    Optional<Mono<ServerResponse>> authDenied = rejectUnlessOwnsTrial(request, trialId.get());
    if (authDenied.isPresent()) {
      return authDenied.get();
    }
    return Mono.fromCallable(
            () -> {
              touch(request, trialId.get());
              store.delete(trialId.get(), provider);
              return Map.of(
                  "ok", true, "provider", CredentialProviders.normalize(provider));
            })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(body -> ServerResponse.ok().bodyValue(body))
        .onErrorResume(this::mapError);
  }

  public void touchFromTrialStart(ServerRequest request, String trialId) {
    if (!store.enabled() || trialId == null || trialId.isBlank()) {
      return;
    }
    touch(request, trialId.trim());
  }

  private CredentialPublicView upsertBody(
      String trialId, String provider, CredentialUpsertRequest body) {
    String normalized = CredentialProviders.normalize(provider);
    Map<String, Object> metadata =
        body.metadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body.metadata());
    Map<String, Object> secrets =
        body.secrets() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body.secrets());
    boolean replaceSecret = !secrets.isEmpty() && hasAnyNonBlank(secrets);
    if (CredentialProviders.ZOHO.equals(normalized) && replaceSecret) {
      if (secrets.containsKey("refreshToken") || secrets.containsKey("clientSecret")) {
        throw new IllegalArgumentException(
            "Zoho OAuth secrets must be stored via /integrations/zoho/exchange-grant");
      }
    }
    return store.upsert(trialId, normalized, secrets, metadata, replaceSecret);
  }

  private void touch(ServerRequest request, String trialId) {
    String clerkUserId = null;
    try {
      if (clerkJwtVerifier.requireClerkJwt()) {
        clerkUserId = clerkJwtVerifier.requireSubject(request);
      }
    } catch (ClerkJwtVerifier.UnauthorizedTrialException ignored) {
      // Caller already validated when required.
    }
    store.touchUser(clerkUserId, trialId);
  }

  private Optional<Mono<ServerResponse>> rejectUnlessOwnsTrial(
      ServerRequest request, String trialId) {
    if (!clerkJwtVerifier.requireClerkJwt()) {
      return Optional.empty();
    }
    try {
      String subject = clerkJwtVerifier.requireSubject(request);
      if (!TrialIdMapper.matches(trialId, subject)) {
        return Optional.of(
            ServerResponse.status(HttpStatus.FORBIDDEN)
                .bodyValue(Map.of("error", "Trial id does not match signed-in user")));
      }
      return Optional.empty();
    } catch (ClerkJwtVerifier.UnauthorizedTrialException ex) {
      return Optional.of(
          ServerResponse.status(HttpStatus.UNAUTHORIZED)
              .bodyValue(Map.of("error", ex.getMessage())));
    }
  }

  private static Optional<String> resolveTrialId(ServerRequest request) {
    String trialId = request.headers().firstHeader(TrialAccessGuard.TRIAL_ID_HEADER);
    if (trialId != null && !trialId.isBlank()) {
      return Optional.of(trialId.trim());
    }
    String service = request.queryParam("service").orElse("").trim();
    return service.isBlank() ? Optional.empty() : Optional.of(service);
  }

  private Map<String, Object> toMap(CredentialPublicView view) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("provider", view.provider());
    map.put("hasSecret", view.hasSecret());
    map.put("status", view.status());
    map.put("metadata", view.metadata() == null ? Map.of() : view.metadata());
    map.put("updatedAt", view.updatedAt() == null ? null : view.updatedAt().toString());
    return map;
  }

  private Mono<ServerResponse> mapError(Throwable ex) {
    if (ex instanceof ClerkJwtVerifier.UnauthorizedTrialException) {
      return ServerResponse.status(HttpStatus.UNAUTHORIZED)
          .bodyValue(Map.of("error", ex.getMessage()));
    }
    if (ex instanceof IllegalArgumentException) {
      return ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage()));
    }
    if (ex instanceof IllegalStateException) {
      return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
          .bodyValue(Map.of("error", ex.getMessage()));
    }
    return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .bodyValue(
            Map.of(
                "error",
                ex.getMessage() == null ? "Credential operation failed" : ex.getMessage()));
  }

  private static boolean hasAnyNonBlank(Map<String, Object> secrets) {
    for (Object value : secrets.values()) {
      if (value != null && !String.valueOf(value).isBlank()) {
        return true;
      }
    }
    return false;
  }

  public record CredentialUpsertRequest(
      Map<String, Object> secrets, Map<String, Object> metadata) {}
}
