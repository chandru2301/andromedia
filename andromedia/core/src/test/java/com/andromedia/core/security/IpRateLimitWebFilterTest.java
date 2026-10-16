package com.andromedia.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class IpRateLimitWebFilterTest {

  @Test
  void allowsRequestsUnderLimit() {
    RateLimitProperties properties = new RateLimitProperties();
    properties.setRequestsPerMinute(3);
    IpRateLimitWebFilter filter = new IpRateLimitWebFilter(properties);
    WebFilterChain chain = mock(WebFilterChain.class);
    when(chain.filter(any())).thenReturn(Mono.empty());

    for (int i = 0; i < 3; i++) {
      MockServerWebExchange exchange =
          MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/investigate").build());
      StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
      assertEquals(null, exchange.getResponse().getStatusCode());
    }
    verify(chain, org.mockito.Mockito.times(3)).filter(any());
  }

  @Test
  void returns429WhenBurstExceedsLimit() {
    RateLimitProperties properties = new RateLimitProperties();
    properties.setRequestsPerMinute(2);
    IpRateLimitWebFilter filter = new IpRateLimitWebFilter(properties);
    WebFilterChain chain = mock(WebFilterChain.class);
    when(chain.filter(any())).thenReturn(Mono.empty());

    MockServerWebExchange first =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/investigate").build());
    MockServerWebExchange second =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/investigate").build());
    MockServerWebExchange third =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/investigate").build());

    StepVerifier.create(filter.filter(first, chain)).verifyComplete();
    StepVerifier.create(filter.filter(second, chain)).verifyComplete();
    StepVerifier.create(filter.filter(third, chain)).verifyComplete();

    assertEquals(HttpStatus.TOO_MANY_REQUESTS, third.getResponse().getStatusCode());
    verify(chain, org.mockito.Mockito.times(2)).filter(any());
  }

  @Test
  void skipsHealthAndBlankIpStillLimited() {
    RateLimitProperties properties = new RateLimitProperties();
    properties.setRequestsPerMinute(1);
    IpRateLimitWebFilter filter = new IpRateLimitWebFilter(properties);
    WebFilterChain chain = mock(WebFilterChain.class);
    when(chain.filter(any())).thenReturn(Mono.empty());

    MockServerWebExchange health =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/health").build());
    StepVerifier.create(filter.filter(health, chain)).verifyComplete();
    verify(chain).filter(health);

    MockServerWebExchange first =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/investigate").build());
    MockServerWebExchange second =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/investigate").build());
    StepVerifier.create(filter.filter(first, chain)).verifyComplete();
    StepVerifier.create(filter.filter(second, chain)).verifyComplete();
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.getResponse().getStatusCode());
  }

  @Test
  void blankIpUsesUnknownBucket() {
    IpRateLimiter limiter = new IpRateLimiter(1);
    assertTrue(limiter.allowRequest(null));
    assertFalse(limiter.allowRequest(""));
    assertFalse(limiter.allowRequest("unknown"));
  }

  @Test
  void exemptsIndexJobStatusGetsButStillLimitsInvestigate() {
    RateLimitProperties properties = new RateLimitProperties();
    properties.setRequestsPerMinute(1);
    IpRateLimitWebFilter filter = new IpRateLimitWebFilter(properties);
    WebFilterChain chain = mock(WebFilterChain.class);
    when(chain.filter(any())).thenReturn(Mono.empty());

    MockServerWebExchange zohoJob =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/index/zoho/job-123").build());
    MockServerWebExchange slackJob =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/index/slack/job-456").build());
    MockServerWebExchange codeJob =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/index/job-789").build());
    MockServerWebExchange overview =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/index/services/local/overview").build());
    MockServerWebExchange investigate =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/investigate").build());
    MockServerWebExchange investigateAgain =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/investigate").build());

    StepVerifier.create(filter.filter(zohoJob, chain)).verifyComplete();
    StepVerifier.create(filter.filter(slackJob, chain)).verifyComplete();
    StepVerifier.create(filter.filter(codeJob, chain)).verifyComplete();
    // Overview is not a job poll — counts against the budget.
    StepVerifier.create(filter.filter(overview, chain)).verifyComplete();
    StepVerifier.create(filter.filter(investigate, chain)).verifyComplete();
    StepVerifier.create(filter.filter(investigateAgain, chain)).verifyComplete();

    assertEquals(null, zohoJob.getResponse().getStatusCode());
    assertEquals(null, slackJob.getResponse().getStatusCode());
    assertEquals(null, codeJob.getResponse().getStatusCode());
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, investigateAgain.getResponse().getStatusCode());
    verify(chain, org.mockito.Mockito.times(4)).filter(any());
  }

  @Test
  void indexJobStatusPathMatcher() {
    assertTrue(IpRateLimitWebFilter.isIndexJobStatusPath("/api/v1/index/abc"));
    assertTrue(IpRateLimitWebFilter.isIndexJobStatusPath("/api/v1/index/zoho/abc"));
    assertTrue(IpRateLimitWebFilter.isIndexJobStatusPath("/api/v1/index/history/abc"));
    assertFalse(IpRateLimitWebFilter.isIndexJobStatusPath("/api/v1/index/services/local/overview"));
    assertFalse(IpRateLimitWebFilter.isIndexJobStatusPath("/api/v1/index/zoho"));
    assertFalse(IpRateLimitWebFilter.isIndexJobStatusPath("/api/v1/investigate"));
  }
}
