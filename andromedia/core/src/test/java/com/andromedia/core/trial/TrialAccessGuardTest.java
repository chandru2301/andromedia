package com.andromedia.core.trial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TrialAccessGuardTest {

  private TrialService trialService;
  private TrialAccessGuard guard;

  @BeforeEach
  void setUp() {
    TrialProperties properties = new TrialProperties();
    properties.setEnabled(true);
    properties.setMaxQueriesPerSession(20);
    properties.setSessionTtlHours(1);
    properties.setRequireClerkJwt(false);
    TrialSessionStore store = new TrialSessionStore(properties);
    store.save(
        new TrialSession(
            "trial-abc",
            Instant.now(),
            Instant.now().plus(1, ChronoUnit.HOURS),
            0,
            20,
            null));
    trialService = new TrialService(properties, store, new TrialUsageLimiter(store));
    guard = new TrialAccessGuard(trialService, new ClerkJwtVerifier(properties));
  }

  @Test
  void allowsMatchingServiceAndTrialId() {
    MockServerRequest request =
        MockServerRequest.builder().header(TrialAccessGuard.TRIAL_ID_HEADER, "trial-abc").build();
    assertTrue(guard.rejectUnlessServiceMatchesTrial(request, "trial-abc").isEmpty());
  }

  @Test
  void rejectsForeignServiceWith403() {
    MockServerRequest request =
        MockServerRequest.builder().header(TrialAccessGuard.TRIAL_ID_HEADER, "trial-abc").build();
    Optional<Mono<ServerResponse>> denied =
        guard.rejectUnlessServiceMatchesTrial(request, "other-trial");
    assertTrue(denied.isPresent());
    StepVerifier.create(denied.get())
        .assertNext(response -> assertEquals(HttpStatus.FORBIDDEN, response.statusCode()))
        .verifyComplete();
  }

  @Test
  void rejectsLocalServiceWith403() {
    MockServerRequest request =
        MockServerRequest.builder().header(TrialAccessGuard.TRIAL_ID_HEADER, "trial-abc").build();
    Optional<Mono<ServerResponse>> denied =
        guard.rejectUnlessServiceMatchesTrial(request, "local");
    assertTrue(denied.isPresent());
    StepVerifier.create(denied.get())
        .assertNext(response -> assertEquals(HttpStatus.FORBIDDEN, response.statusCode()))
        .verifyComplete();
  }

  @Test
  void rejectsMissingHeaderWith400() {
    MockServerRequest request = MockServerRequest.builder().build();
    Optional<Mono<ServerResponse>> denied =
        guard.rejectUnlessServiceMatchesTrial(request, "trial-abc");
    assertTrue(denied.isPresent());
    StepVerifier.create(denied.get())
        .assertNext(response -> assertEquals(HttpStatus.BAD_REQUEST, response.statusCode()))
        .verifyComplete();
  }

  @Test
  void passesThroughWhenTrialDisabled() {
    TrialProperties properties = new TrialProperties();
    properties.setEnabled(false);
    properties.setRequireClerkJwt(false);
    TrialSessionStore store = new TrialSessionStore(properties);
    TrialService disabled =
        new TrialService(properties, store, new TrialUsageLimiter(store));
    TrialAccessGuard disabledGuard =
        new TrialAccessGuard(disabled, new ClerkJwtVerifier(properties));
    MockServerRequest request = MockServerRequest.builder().build();
    assertTrue(disabledGuard.rejectUnlessServiceMatchesTrial(request, "local").isEmpty());
  }

  @Test
  void rejectsMissingBearerWhenClerkRequired() {
    TrialProperties properties = new TrialProperties();
    properties.setEnabled(true);
    properties.setRequireClerkJwt(true);
    properties.setClerkJwksUrl("https://example.clerk.accounts.dev/.well-known/jwks.json");
    TrialSessionStore store = new TrialSessionStore(properties);
    store.save(
        new TrialSession(
            "user_abc",
            Instant.now(),
            Instant.now().plus(1, ChronoUnit.HOURS),
            0,
            20,
            null));
    TrialService service = new TrialService(properties, store, new TrialUsageLimiter(store));
    TrialAccessGuard clerkGuard = new TrialAccessGuard(service, new ClerkJwtVerifier(properties));
    MockServerRequest request =
        MockServerRequest.builder().header(TrialAccessGuard.TRIAL_ID_HEADER, "user_abc").build();
    Optional<Mono<ServerResponse>> denied =
        clerkGuard.rejectUnlessServiceMatchesTrial(request, "user_abc");
    assertTrue(denied.isPresent());
    StepVerifier.create(denied.get())
        .assertNext(response -> assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode()))
        .verifyComplete();
  }
}
