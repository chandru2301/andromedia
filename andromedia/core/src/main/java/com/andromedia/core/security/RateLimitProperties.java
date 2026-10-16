package com.andromedia.core.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "andromedia.rate-limit")
public class RateLimitProperties {

  /** Max requests per IP per rolling minute window for /api/** (except health). */
  private int requestsPerMinute = 30;

  private boolean enabled = true;

  public int requestsPerMinute() {
    return requestsPerMinute;
  }

  public void setRequestsPerMinute(int requestsPerMinute) {
    this.requestsPerMinute = requestsPerMinute <= 0 ? 30 : requestsPerMinute;
  }

  public boolean enabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }
}
