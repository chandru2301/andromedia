package com.andromedia.core;

import com.andromedia.core.index.GitIngestionProperties;
import com.andromedia.core.index.GoogleChatIngestionProperties;
import com.andromedia.core.index.JiraIngestionProperties;
import com.andromedia.core.index.LinearIngestionProperties;
import com.andromedia.core.index.ZohoIngestionProperties;
import com.andromedia.core.index.NotionIngestionProperties;
import com.andromedia.core.index.SlackIngestionProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
@EnableConfigurationProperties({
  GitIngestionProperties.class,
  SlackIngestionProperties.class,
  NotionIngestionProperties.class,
  JiraIngestionProperties.class,
  LinearIngestionProperties.class,
  ZohoIngestionProperties.class,
  GoogleChatIngestionProperties.class,
  com.andromedia.core.trial.TrialProperties.class,
  com.andromedia.core.security.RateLimitProperties.class,
  com.andromedia.core.alerts.PipelineAlertProperties.class,
  com.andromedia.core.credentials.CredentialsProperties.class,
  com.andromedia.core.oauth.OAuthProperties.class
})
public class CoreConfiguration {

  /**
   * Browser CORS only (set ANDRO_CORS_ORIGINS, e.g. https://andro-app.vercel.app). Curl/scripts
   * ignore CORS — IpRateLimitWebFilter and TrialAccessGuard are the real abuse controls.
   */
  @Bean
  CorsWebFilter corsWebFilter(
      @Value("${ANDRO_CORS_ORIGINS:}") String extraOrigins) {
    CorsConfiguration config = new CorsConfiguration();
    List<String> origins = new ArrayList<>(
        List.of(
            "http://localhost:5173",
            "http://localhost:3000",
            "http://127.0.0.1:3000",
            "http://127.0.0.1:5173",
            "http://localhost:4173",
            "http://127.0.0.1:4173"));
    if (extraOrigins != null && !extraOrigins.isBlank()) {
      origins.addAll(
          Arrays.stream(extraOrigins.split(","))
              .map(String::trim)
              .filter(origin -> !origin.isBlank())
              .collect(Collectors.toList()));
    }
    config.setAllowedOrigins(origins);
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Content-Type", "Authorization", "Accept", "X-Trial-Id"));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return new CorsWebFilter(source);
  }
}
