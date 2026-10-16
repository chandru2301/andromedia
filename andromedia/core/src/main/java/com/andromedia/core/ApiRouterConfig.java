package com.andromedia.core;

import com.andromedia.core.alerts.PipelineAlertStore;
import com.andromedia.core.alerts.SlackMessageService;
import com.andromedia.core.credentials.CredentialApiService;
import com.andromedia.core.credentials.CredentialProviders;
import com.andromedia.core.credentials.UserCredentialStore;
import com.andromedia.core.credentials.ZohoReauthRequiredException;
import com.andromedia.core.index.ActiveZohoIndexStore;
import com.andromedia.core.index.GitHistoryJobService;
import com.andromedia.core.index.GitHistoryJobStore;
import com.andromedia.core.index.IndexJobService;
import com.andromedia.core.index.IndexJobStore;
import com.andromedia.core.index.JiraConnectionTestService;
import com.andromedia.core.index.JiraIngestionJobService;
import com.andromedia.core.index.JiraIngestionJobStore;
import com.andromedia.core.index.LinearConnectionTestService;
import com.andromedia.core.index.LinearIngestionJobService;
import com.andromedia.core.index.LinearIngestionJobStore;
import com.andromedia.core.index.ZohoConnectionTestService;
import com.andromedia.core.index.ZohoIngestionJobService;
import com.andromedia.core.index.ZohoIngestionJobStore;
import com.andromedia.core.index.NotionConnectionTestService;
import com.andromedia.core.index.NotionIngestionJobService;
import com.andromedia.core.index.NotionIngestionJobStore;
import com.andromedia.core.index.ServiceIndexMetadataStore;
import com.andromedia.core.index.ServiceIndexOverviewService;
import com.andromedia.core.index.SlackConnectionTestService;
import com.andromedia.core.index.SlackIngestionJobService;
import com.andromedia.core.index.SlackIngestionJobStore;
import com.andromedia.core.investigate.InvestigationProgressEvent;
import com.andromedia.core.investigate.InvestigationProgressSink;
import com.andromedia.core.investigate.InvestigationRateLimitException;
import com.andromedia.core.investigate.InvestigationService;
import com.andromedia.core.trial.TrialAccessGuard;
import com.andromedia.core.trial.TrialService;
import com.andromedia.core.trial.TrialSession;
import com.andromedia.common.IndexPathHolder;
import com.andromedia.llm.ConfigurableChatService;
import com.andromedia.search.SearchHit;
import com.andromedia.search.SearchMode;
import com.andromedia.search.SearchRequest;
import com.andromedia.search.SearchResult;
import com.andromedia.search.SearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@Configuration
public class ApiRouterConfig {

  @Bean
  RouterFunction<ServerResponse> apiRouter(
      IndexJobService indexJobService,
      GitHistoryJobService gitHistoryJobService,
      SlackIngestionJobService slackIngestionJobService,
      SlackConnectionTestService slackConnectionTestService,
      SlackMessageService slackMessageService,
      PipelineAlertStore pipelineAlertStore,
      NotionIngestionJobService notionIngestionJobService,
      NotionConnectionTestService notionConnectionTestService,
      JiraIngestionJobService jiraIngestionJobService,
      JiraConnectionTestService jiraConnectionTestService,
      LinearIngestionJobService linearIngestionJobService,
      LinearConnectionTestService linearConnectionTestService,
      ZohoIngestionJobService zohoIngestionJobService,
      ZohoConnectionTestService zohoConnectionTestService,
      com.andromedia.core.index.GoogleChatIngestionJobService googleChatIngestionJobService,
      com.andromedia.core.index.GoogleChatConnectionTestService googleChatConnectionTestService,
      ServiceIndexMetadataStore metadataStore,
      ServiceIndexOverviewService overviewService,
      com.andromedia.core.index.WorkspaceCandidateService workspaceCandidateService,
      InvestigationService investigationService,
      SearchService searchService,
      ActiveZohoIndexStore activeZohoIndexStore,
      ConfigurableChatService configurableChatService,
      TrialService trialService,
      TrialAccessGuard trialAccessGuard,
      com.andromedia.core.search.SearchAnswerService searchAnswerService,
      CredentialApiService credentialApiService,
      UserCredentialStore userCredentialStore,
      com.andromedia.core.oauth.OAuthService oAuthService) {
    return route(GET("/api/v1/health"), request -> ServerResponse.ok().bodyValue(Map.of("status", "UP")))
        .andRoute(GET("/api/v1/credentials"), credentialApiService::list)
        .andRoute(
            GET("/api/v1/oauth/{provider}/authorize"),
            request -> oAuthAuthorize(request, oAuthService))
        .andRoute(
            GET("/api/v1/oauth/{provider}/callback"),
            request -> oAuthCallback(request, oAuthService))
        .andRoute(
            GET("/api/v1/oauth/{provider}/status"), request -> oAuthStatus(request, oAuthService))
        .andRoute(PUT("/api/v1/credentials/{provider}"), credentialApiService::upsert)
        .andRoute(DELETE("/api/v1/credentials/{provider}"), credentialApiService::delete)
        .andRoute(POST("/api/v1/index"), request -> startIndex(request, indexJobService, trialAccessGuard))
        .andRoute(GET("/api/v1/index/{jobId}"), request -> getIndexJob(request, indexJobService))
        .andRoute(
            POST("/api/v1/index/history"),
            request -> startHistoryIndex(request, gitHistoryJobService, trialAccessGuard))
        .andRoute(
            GET("/api/v1/index/history/{jobId}"),
            request -> getHistoryIndexJob(request, gitHistoryJobService))
        .andRoute(
            POST("/api/v1/index/slack"),
            request ->
                startSlackIndex(
                    request, slackIngestionJobService, trialAccessGuard, userCredentialStore))
        .andRoute(
            GET("/api/v1/index/slack/{jobId}"),
            request -> getSlackIndexJob(request, slackIngestionJobService))
        .andRoute(
            POST("/api/v1/integrations/slack/test"),
            request -> testSlackConnection(request, slackConnectionTestService, userCredentialStore))
        .andRoute(
            POST("/api/v1/integrations/slack/channels"),
            request -> listSlackChannels(request, slackMessageService, userCredentialStore))
        .andRoute(
            POST("/api/v1/integrations/slack/message"),
            request ->
                sendSlackMessage(
                    request, slackMessageService, trialAccessGuard, userCredentialStore))
        .andRoute(
            GET("/api/v1/alerts/slack"),
            request -> getSlackAlertConfig(request, pipelineAlertStore, trialAccessGuard))
        .andRoute(
            PUT("/api/v1/alerts/slack"),
            request -> putSlackAlertConfig(request, pipelineAlertStore, trialAccessGuard))
        .andRoute(
            POST("/api/v1/index/notion"),
            request ->
                startNotionIndex(
                    request, notionIngestionJobService, trialAccessGuard, userCredentialStore))
        .andRoute(
            GET("/api/v1/index/notion/{jobId}"),
            request -> getNotionIndexJob(request, notionIngestionJobService))
        .andRoute(
            POST("/api/v1/integrations/notion/test"),
            request ->
                testNotionConnection(request, notionConnectionTestService, userCredentialStore))
        .andRoute(
            POST("/api/v1/index/jira"),
            request ->
                startJiraIndex(
                    request,
                    jiraIngestionJobService,
                    trialAccessGuard,
                    userCredentialStore,
                    oAuthService))
        .andRoute(
            GET("/api/v1/index/jira/{jobId}"),
            request -> getJiraIndexJob(request, jiraIngestionJobService))
        .andRoute(
            POST("/api/v1/integrations/jira/test"),
            request ->
                testJiraConnection(
                    request, jiraConnectionTestService, userCredentialStore, oAuthService))
        .andRoute(
            POST("/api/v1/index/linear"),
            request ->
                startLinearIndex(
                    request, linearIngestionJobService, trialAccessGuard, userCredentialStore))
        .andRoute(
            GET("/api/v1/index/linear/{jobId}"),
            request -> getLinearIndexJob(request, linearIngestionJobService))
        .andRoute(
            POST("/api/v1/integrations/linear/test"),
            request ->
                testLinearConnection(request, linearConnectionTestService, userCredentialStore))
        .andRoute(
            POST("/api/v1/integrations/zoho/test"),
            request -> testZohoConnection(request, zohoConnectionTestService, trialAccessGuard))
        .andRoute(
            POST("/api/v1/index/zoho"),
            request -> startZohoIndex(request, zohoIngestionJobService, trialAccessGuard))
        .andRoute(
            GET("/api/v1/index/zoho/{jobId}"),
            request -> getZohoIndexJob(request, zohoIngestionJobService))
        .andRoute(
            POST("/api/v1/index/google-chat"),
            request ->
                startGoogleChatIndex(
                    request, googleChatIngestionJobService, trialAccessGuard, userCredentialStore))
        .andRoute(
            GET("/api/v1/index/google-chat/{jobId}"),
            request -> getGoogleChatIndexJob(request, googleChatIngestionJobService))
        .andRoute(
            POST("/api/v1/integrations/google-chat/test"),
            request ->
                testGoogleChatConnection(
                    request, googleChatConnectionTestService, userCredentialStore))
        .andRoute(
            GET("/api/v1/index/services/{service}/overview"),
            request -> getServiceIndexOverview(request, overviewService, trialAccessGuard))
        // NOTE: this must NOT live at /api/v1/index/services — that path has the
        // same depth as /api/v1/index/{jobId}, which is registered earlier and
        // would swallow it as a job lookup, returning 404.
        .andRoute(
            GET("/api/v1/workspaces"),
            request -> listWorkspaces(request, overviewService, trialAccessGuard))
        // Projects available under the read-only workspace mount, so the app can
        // offer "index this project" without asking for a host path.
        .andRoute(
            GET("/api/v1/workspaces/candidates"),
            request -> listWorkspaceCandidates(request, workspaceCandidateService, trialAccessGuard))
        .andRoute(
            GET("/api/v1/index/services/{service}"),
            request -> getServiceIndex(request, metadataStore, trialAccessGuard))
        .andRoute(
            GET("/api/v1/settings/chat-provider"),
            request ->
                getChatProvider(
                    request, configurableChatService, trialService, userCredentialStore))
        .andRoute(
            PUT("/api/v1/settings/chat-provider"),
            request ->
                updateChatProvider(
                    request, configurableChatService, trialService, userCredentialStore))
        .andRoute(
            GET("/api/v1/trial/config"),
            request -> ServerResponse.ok().bodyValue(trialService.config()))
        .andRoute(
            POST("/api/v1/trial/start"),
            request ->
                startTrialSession(request, trialService, trialAccessGuard, credentialApiService))
        .andRoute(
            GET("/api/v1/trial/status/{trialId}"),
            request -> getTrialStatus(request, trialService, trialAccessGuard))
        .andRoute(
            POST("/api/v1/search"),
            request -> search(request, searchService, activeZohoIndexStore, trialAccessGuard))
        // Same retrieval as /search, then synthesized into prose. Kept separate so
        // the raw-hit path stays cheap and available when no model is configured.
        .andRoute(
            POST("/api/v1/search/answer"),
            request ->
                searchAnswer(
                    request,
                    searchService,
                    activeZohoIndexStore,
                    searchAnswerService,
                    trialService,
                    trialAccessGuard,
                    configurableChatService,
                    userCredentialStore))
        .andRoute(
            POST("/api/v1/investigate"),
            request ->
                investigate(
                    request,
                    investigationService,
                    trialService,
                    trialAccessGuard,
                    configurableChatService,
                    userCredentialStore))
        .andRoute(
            POST("/api/v1/investigate/stream"),
            request ->
                investigateStream(
                    request,
                    investigationService,
                    trialService,
                    trialAccessGuard,
                    configurableChatService,
                    userCredentialStore));
  }

  private Mono<ServerResponse> getChatProvider(
      ServerRequest request,
      ConfigurableChatService configurableChatService,
      TrialService trialService,
      UserCredentialStore userCredentialStore) {
    if (trialService.enabled()) {
      return ServerResponse.ok().bodyValue(trialChatProviderView(configurableChatService));
    }
    ConfigurableChatService.ProviderSelection selection = configurableChatService.selection();
    String trialId = chatCredentialOwner(request, trialService);
    if (!userCredentialStore.enabled() || trialId.isBlank()) {
      return ServerResponse.ok().bodyValue(selection);
    }
    List<Map<String, Object>> providers =
        selection.providers().stream()
            .map(
                provider -> {
                  boolean storedKey =
                      userCredentialStore
                          .resolveLlmApiKey(trialId, provider.id())
                          .isPresent();
                  Map<String, Object> row = new HashMap<>();
                  row.put("id", provider.id());
                  row.put("label", provider.label());
                  row.put("model", provider.model());
                  row.put("configured", provider.configured() || storedKey);
                  row.put("apiKeyConfigured", provider.apiKeyConfigured() || storedKey);
                  row.put("active", provider.active());
                  row.put("availableModels", provider.availableModels());
                  return row;
                })
            .toList();
    Map<String, Object> result = new HashMap<>();
    result.put("activeProvider", selection.activeProvider());
    result.put("providers", providers);
    return ServerResponse.ok().bodyValue(result);
  }

  private static String trialIdHeader(ServerRequest request) {
    String trialId = request.headers().firstHeader(TrialAccessGuard.TRIAL_ID_HEADER);
    return trialId == null ? "" : trialId.trim();
  }

  /** Vault owner for LLM keys: trial header, or {@code local} for self-hosted Settings saves. */
  private static String chatCredentialOwner(ServerRequest request, TrialService trialService) {
    String trialId = trialIdHeader(request);
    if (!trialId.isBlank()) {
      return trialId;
    }
    return trialService.enabled() ? "" : "local";
  }

  /**
   * Loads a per-user OpenRouter/Groq key from Postgres. In-memory {@code OPENROUTER_API_KEY} is
   * empty after restart, so investigate must apply this as a request override.
   */
  private static String resolveInvestigateLlmApiKey(
      ServerRequest request,
      TrialService trialService,
      UserCredentialStore userCredentialStore,
      ConfigurableChatService configurableChatService,
      String service) {
    if (!userCredentialStore.enabled()) {
      return null;
    }
    String provider = configurableChatService.selection().activeProvider();
    String owner = chatCredentialOwner(request, trialService);
    if (!owner.isBlank()) {
      Optional<String> stored = userCredentialStore.resolveLlmApiKey(owner, provider);
      if (stored.isPresent()) {
        return stored.get();
      }
    }
    if (service != null && !service.isBlank() && !service.equals(owner)) {
      return userCredentialStore.resolveLlmApiKey(service, provider).orElse(null);
    }
    return null;
  }

  private Map<String, Object> trialChatProviderView(ConfigurableChatService configurableChatService) {
    ConfigurableChatService.ProviderSelection selection = configurableChatService.selection();
    List<Map<String, Object>> providers =
        selection.providers().stream()
            .map(
                provider -> {
                  Map<String, Object> row = new HashMap<>();
                  row.put("id", provider.id());
                  row.put("label", provider.label());
                  row.put("model", provider.model());
                  row.put("configured", true);
                  row.put("apiKeyConfigured", true);
                  row.put("active", provider.active());
                  row.put("availableModels", provider.availableModels());
                  return row;
                })
            .toList();
    Map<String, Object> result = new HashMap<>();
    result.put("activeProvider", selection.activeProvider());
    result.put("providers", providers);
    result.put("trialMode", true);
    return result;
  }

  private Mono<ServerResponse> startTrialSession(
      ServerRequest request,
      TrialService trialService,
      TrialAccessGuard trialAccessGuard,
      CredentialApiService credentialApiService) {
    if (!trialService.enabled()) {
      return ServerResponse.notFound().build();
    }
    return request
        .bodyToMono(TrialStartRequest.class)
        .flatMap(
            body -> {
              if (body.trialId() == null || body.trialId().isBlank()) {
                return ServerResponse.badRequest().bodyValue(Map.of("error", "trialId is required"));
              }
              Optional<Mono<ServerResponse>> denied =
                  trialAccessGuard.rejectUnlessClerkOwnsTrial(request, body.trialId().trim());
              if (denied.isPresent()) {
                return denied.get();
              }
              return Mono.fromCallable(
                      () -> {
                        TrialSession session =
                            trialService.startSession(body.trialId(), clientIp(request));
                        credentialApiService.touchFromTrialStart(request, body.trialId().trim());
                        return trialService.sessionView(session);
                      })
                  .subscribeOn(Schedulers.boundedElastic())
                  .flatMap(view -> ServerResponse.ok().bodyValue(view));
            })
        .switchIfEmpty(
            ServerResponse.badRequest().bodyValue(Map.of("error", "Request body is required")))
        .onErrorResume(
            TrialService.TrialNotEnabledException.class,
            ex -> ServerResponse.notFound().build())
        .onErrorResume(
            IllegalArgumentException.class,
            ex -> ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage())));
  }

  private Mono<ServerResponse> getTrialStatus(
      ServerRequest request, TrialService trialService, TrialAccessGuard trialAccessGuard) {
    if (!trialService.enabled()) {
      return ServerResponse.notFound().build();
    }
    String trialId = request.pathVariable("trialId");
    Optional<Mono<ServerResponse>> denied =
        trialAccessGuard.rejectUnlessClerkOwnsTrial(request, trialId);
    if (denied.isPresent()) {
      return denied.get();
    }
    return Mono.fromCallable(() -> trialService.status(trialId))
        .map(trialService::sessionView)
        .flatMap(view -> ServerResponse.ok().bodyValue(view))
        .onErrorResume(
            TrialService.TrialNotEnabledException.class,
            ex -> ServerResponse.notFound().build())
        .onErrorResume(
            IllegalArgumentException.class,
            ex -> ServerResponse.status(HttpStatus.NOT_FOUND).bodyValue(Map.of("error", ex.getMessage())));
  }

  private static String clientIp(ServerRequest request) {
    String forwarded = request.headers().firstHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    if (request.remoteAddress().isPresent()) {
      return request.remoteAddress().get().getAddress().getHostAddress();
    }
    return "";
  }

  private Mono<ServerResponse> updateChatProvider(
      ServerRequest request,
      ConfigurableChatService configurableChatService,
      TrialService trialService,
      UserCredentialStore userCredentialStore) {
    if (trialService.enabled()) {
      return ServerResponse.status(HttpStatus.FORBIDDEN)
          .bodyValue(Map.of("error", "Trial mode: LLM is provided by Andro"));
    }
    return request
        .bodyToMono(ChatProviderRequest.class)
        .flatMap(
            body ->
                Mono.fromCallable(
                        () -> {
                          String owner = chatCredentialOwner(request, trialService);
                          String provider =
                              body.provider() == null || body.provider().isBlank()
                                  ? configurableChatService.selection().activeProvider()
                                  : body.provider();
                          String requestedKey = body.apiKey();
                          String effectiveKey = requestedKey;
                          if ((effectiveKey == null || effectiveKey.isBlank())
                              && userCredentialStore.enabled()
                              && !owner.isBlank()) {
                            effectiveKey =
                                userCredentialStore.resolveLlmApiKey(owner, provider).orElse(null);
                          }
                          ConfigurableChatService.ProviderSelection selection =
                              configurableChatService.update(
                                  provider, body.model(), effectiveKey);
                          if (userCredentialStore.enabled()
                              && !owner.isBlank()
                              && requestedKey != null
                              && !requestedKey.isBlank()) {
                            Map<String, Object> metadata = new LinkedHashMap<>();
                            if (body.model() != null && !body.model().isBlank()) {
                              metadata.put("model", body.model().trim());
                            }
                            userCredentialStore.upsert(
                                owner,
                                CredentialProviders.llmProvider(provider),
                                Map.of("apiKey", requestedKey.trim()),
                                metadata,
                                true);
                          }
                          return selection;
                        })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(selection -> ServerResponse.ok().bodyValue(selection)))
        .switchIfEmpty(
            ServerResponse.badRequest().bodyValue(Map.of("error", "Request body is required")))
        .onErrorResume(
            IllegalArgumentException.class,
            ex -> ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage())));
  }

  private Mono<ServerResponse> startIndex(
      ServerRequest request, IndexJobService indexJobService, TrialAccessGuard trialAccessGuard) {
    return request
        .bodyToMono(IndexRequest.class)
        .flatMap(
            body -> {
              if (body.service() == null || body.service().isBlank()) {
                return ServerResponse.badRequest().build();
              }
              Optional<Mono<ServerResponse>> denied =
                  trialAccessGuard.rejectUnlessServiceMatchesTrial(request, body.service());
              if (denied.isPresent()) {
                return denied.get();
              }
              boolean hasRepo = body.repo() != null && !body.repo().isBlank();
              boolean hasLocal = body.localPath() != null && !body.localPath().isBlank();
              if (hasRepo == hasLocal) {
                return ServerResponse.badRequest()
                    .bodyValue(Map.of("error", "Exactly one of repo or localPath is required"));
              }
              boolean embed = body.embed() == null || body.embed();
              IndexJobStore.IndexJob job;
              if (hasLocal) {
                job =
                    indexJobService.enqueueLocal(
                        body.service().trim(), body.localPath().trim(), embed);
              } else {
                job =
                    indexJobService.enqueueGit(
                        body.service().trim(),
                        body.repo().trim(),
                        body.branch(),
                        body.repoSubpath(),
                        embed);
              }
              Map<String, Object> response =
                  Map.of("jobId", job.jobId(), "service", job.service(), "status", job.status());
              return ServerResponse.status(HttpStatus.ACCEPTED).bodyValue(response);
            })
        .onErrorResume(ex -> ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage())));
  }

  private Mono<ServerResponse> getIndexJob(ServerRequest request, IndexJobService indexJobService) {
    String jobId = request.pathVariable("jobId");
    IndexJobStore.IndexJob job = indexJobService.getJob(jobId);
    if (job == null) {
      return ServerResponse.notFound().build();
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("jobId", job.jobId());
    response.put("service", job.service());
    response.put("status", job.status());
    response.put("stats", job.stats());
    response.put("error", job.error());
    return ServerResponse.ok().bodyValue(response);
  }

  private Mono<ServerResponse> getServiceIndex(
      ServerRequest request,
      ServiceIndexMetadataStore metadataStore,
      TrialAccessGuard trialAccessGuard) {
    String service = request.pathVariable("service");
    Optional<Mono<ServerResponse>> denied =
        trialAccessGuard.rejectUnlessServiceMatchesTrial(request, service);
    if (denied.isPresent()) {
      return denied.get();
    }
    ServiceIndexMetadataStore.ServiceIndexMetadata metadata = metadataStore.get(service);
    if (metadata == null) {
      return ServerResponse.notFound().build();
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("service", metadata.service());
    response.put("indexedAt", metadata.indexedAt().toString());
    response.put("stats", metadata.stats());
    return ServerResponse.ok().bodyValue(response);
  }

  private Mono<ServerResponse> getServiceIndexOverview(
      ServerRequest request,
      ServiceIndexOverviewService overviewService,
      TrialAccessGuard trialAccessGuard) {
    String service = request.pathVariable("service");
    Optional<Mono<ServerResponse>> denied =
        trialAccessGuard.rejectUnlessServiceMatchesTrial(request, service);
    if (denied.isPresent()) {
      return denied.get();
    }
    return Mono.fromCallable(() -> overviewService.overview(service))
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(body -> ServerResponse.ok().bodyValue(body));
  }

  /**
   * List every workspace with an index on disk.
   *
   * <p>In trial mode the caller must present their {@code X-Trial-Id} and only
   * that workspace is returned. Without this filter the endpoint would enumerate
   * every other tenant's workspace names, which is what the per-route trial guard
   * exists to prevent.
   */
  /**
   * Projects the API may index, discovered under the read-only workspace mount.
   *
   * <p>Trial mode rejects the call outright rather than filtering: a trial user
   * has no host repositories, and the mount belongs to the operator.
   */
  private Mono<ServerResponse> listWorkspaceCandidates(
      ServerRequest request,
      com.andromedia.core.index.WorkspaceCandidateService candidateService,
      TrialAccessGuard trialAccessGuard) {
    if (trialAccessGuard.trialEnabled()) {
      return ServerResponse.status(HttpStatus.FORBIDDEN)
          .bodyValue(
              Map.of("error", "Local project indexing is unavailable in trial mode"));
    }
    return Mono.fromCallable(candidateService::candidates)
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(rows -> ServerResponse.ok().bodyValue(Map.of("candidates", rows)));
  }

  private Mono<ServerResponse> listWorkspaces(
      ServerRequest request,
      ServiceIndexOverviewService overviewService,
      TrialAccessGuard trialAccessGuard) {
    boolean trial = trialAccessGuard.trialEnabled();
    String trialId = trial ? request.headers().firstHeader(TrialAccessGuard.TRIAL_ID_HEADER) : null;

    if (trial && (trialId == null || trialId.isBlank())) {
      return ServerResponse.badRequest()
          .bodyValue(Map.of("error", "X-Trial-Id header is required in trial mode"));
    }

    return Mono.fromCallable(
            () -> {
              List<Map<String, Object>> all = overviewService.list();
              if (trialId == null || trialId.isBlank()) {
                return all;
              }
              String wanted = trialId.trim();
              return all.stream().filter(row -> wanted.equals(row.get("service"))).toList();
            })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(rows -> ServerResponse.ok().bodyValue(Map.of("workspaces", rows)));
  }

  private Mono<ServerResponse> startHistoryIndex(
      ServerRequest request,
      GitHistoryJobService gitHistoryJobService,
      TrialAccessGuard trialAccessGuard) {
    return request
        .bodyToMono(HistoryIndexRequest.class)
        .flatMap(
            body -> {
              if (body.service() == null || body.service().isBlank()) {
                return ServerResponse.badRequest().build();
              }
              Optional<Mono<ServerResponse>> denied =
                  trialAccessGuard.rejectUnlessServiceMatchesTrial(request, body.service());
              if (denied.isPresent()) {
                return denied.get();
              }
              if (body.localPath() == null || body.localPath().isBlank()) {
                return ServerResponse.badRequest()
                    .bodyValue(Map.of("error", "localPath is required"));
              }
              boolean embed = body.embed() == null || body.embed();
              try {
                GitHistoryJobStore.GitHistoryJob job =
                    gitHistoryJobService.enqueueLocalHistory(
                        body.service().trim(),
                        body.localPath().trim(),
                        embed,
                        body.maxCommits(),
                        body.since());
                Map<String, Object> response =
                    Map.of("jobId", job.jobId(), "service", job.service(), "status", job.status());
                return ServerResponse.status(HttpStatus.ACCEPTED).bodyValue(response);
              } catch (IllegalArgumentException ex) {
                return ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage()));
              }
            })
        .onErrorResume(ex -> ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage())));
  }

  private Mono<ServerResponse> getHistoryIndexJob(
      ServerRequest request, GitHistoryJobService gitHistoryJobService) {
    String jobId = request.pathVariable("jobId");
    GitHistoryJobStore.GitHistoryJob job = gitHistoryJobService.getJob(jobId);
    if (job == null) {
      return ServerResponse.notFound().build();
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("jobId", job.jobId());
    response.put("service", job.service());
    response.put("status", job.status());
    response.put("stats", job.stats());
    response.put("error", job.error());
    return ServerResponse.ok().bodyValue(response);
  }

  private Mono<ServerResponse> startSlackIndex(
      ServerRequest request,
      SlackIngestionJobService slackIngestionJobService,
      TrialAccessGuard trialAccessGuard,
      UserCredentialStore userCredentialStore) {
    return request
        .bodyToMono(SlackIndexRequest.class)
        .flatMap(
            body -> {
              if (body.service() == null || body.service().isBlank()) {
                return ServerResponse.badRequest().build();
              }
              Optional<Mono<ServerResponse>> denied =
                  trialAccessGuard.rejectUnlessServiceMatchesTrial(request, body.service());
              if (denied.isPresent()) {
                return denied.get();
              }
              boolean embed = body.embed() == null || body.embed();
              List<String> channels = parseChannels(body.channels());
              try {
                String botToken =
                    userCredentialStore
                        .resolveSlackBotToken(body.service().trim(), body.botToken())
                        .orElse(body.botToken());
                if (botToken == null || botToken.isBlank()) {
                  return ServerResponse.badRequest()
                      .bodyValue(Map.of("error", "botToken is required (or save it via credentials)"));
                }
                if (userCredentialStore.enabled()
                    && body.botToken() != null
                    && !body.botToken().isBlank()) {
                  Map<String, Object> metadata = new LinkedHashMap<>();
                  metadata.put("channels", channels);
                  if (body.lookbackDays() != null) {
                    metadata.put("lookbackDays", body.lookbackDays());
                  }
                  userCredentialStore.upsert(
                      body.service().trim(),
                      CredentialProviders.SLACK,
                      Map.of("botToken", body.botToken().trim()),
                      metadata,
                      true);
                }
                SlackIngestionJobStore.SlackIngestionJob job =
                    slackIngestionJobService.enqueue(
                        body.service().trim(),
                        botToken,
                        channels,
                        embed,
                        body.lookbackDays(),
                        body.workspaceRoot());
                Map<String, Object> response =
                    Map.of("jobId", job.jobId(), "service", job.service(), "status", job.status());
                return ServerResponse.status(HttpStatus.ACCEPTED).bodyValue(response);
              } catch (IllegalArgumentException ex) {
                return ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage()));
              }
            })
        .onErrorResume(ex -> ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage())));
  }

  private Mono<ServerResponse> getSlackIndexJob(
      ServerRequest request, SlackIngestionJobService slackIngestionJobService) {
    String jobId = request.pathVariable("jobId");
    SlackIngestionJobStore.SlackIngestionJob job = slackIngestionJobService.getJob(jobId);
    if (job == null) {
      return ServerResponse.notFound().build();
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("jobId", job.jobId());
    response.put("service", job.service());
    response.put("status", job.status());
    response.put("stats", job.stats());
    response.put("error", job.error());
    return ServerResponse.ok().bodyValue(response);
  }

  private Mono<ServerResponse> testSlackConnection(
      ServerRequest request,
      SlackConnectionTestService slackConnectionTestService,
      UserCredentialStore userCredentialStore) {
    return request
        .bodyToMono(SlackTestRequest.class)
        .flatMap(
            body ->
                Mono.fromCallable(
                        () -> {
                          List<String> channels =
                              SlackConnectionTestService.parseChannels(body.channels());
                          String service = trialIdHeader(request);
                          String botToken =
                              userCredentialStore
                                  .resolveSlackBotToken(service, body.botToken())
                                  .orElse(body.botToken());
                          return slackConnectionTestService.test(botToken, channels);
                        })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(
                        result -> {
                          Map<String, Object> response = new LinkedHashMap<>();
                          response.put("ok", result.ok());
                          response.put("team", result.team());
                          response.put("teamId", result.teamId());
                          response.put("user", result.user());
                          response.put("resolvedChannels", result.resolvedChannels());
                          response.put("missingChannels", result.missingChannels());
                          return ServerResponse.ok().bodyValue(response);
                        }))
        .onErrorResume(
            IllegalArgumentException.class,
            ex -> ServerResponse.badRequest().bodyValue(Map.of("ok", false, "error", ex.getMessage())))
        .onErrorResume(
            ex ->
                ServerResponse.status(HttpStatus.BAD_GATEWAY)
                    .bodyValue(
                        Map.of(
                            "ok",
                            false,
                            "error",
                            ex.getMessage() == null ? "Slack test failed" : ex.getMessage())));
  }

  private Mono<ServerResponse> listSlackChannels(
      ServerRequest request,
      SlackMessageService slackMessageService,
      UserCredentialStore userCredentialStore) {
    return request
        .bodyToMono(SlackChannelsRequest.class)
        .flatMap(
            body ->
                Mono.fromCallable(
                        () -> {
                          String service = trialIdHeader(request);
                          String botToken =
                              userCredentialStore
                                  .resolveSlackBotToken(service, body.botToken())
                                  .orElse(body.botToken());
                          return slackMessageService.listChannels(botToken);
                        })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(
                        channels ->
                            ServerResponse.ok()
                                .bodyValue(Map.of("ok", true, "channels", channels))))
        .onErrorResume(
            IllegalArgumentException.class,
            ex -> ServerResponse.badRequest().bodyValue(Map.of("ok", false, "error", ex.getMessage())))
        .onErrorResume(
            ex ->
                ServerResponse.status(HttpStatus.BAD_GATEWAY)
                    .bodyValue(
                        Map.of(
                            "ok",
                            false,
                            "error",
                            ex.getMessage() == null
                                ? "Unable to list Slack channels"
                                : ex.getMessage())));
  }

  private Mono<ServerResponse> sendSlackMessage(
      ServerRequest request,
      SlackMessageService slackMessageService,
      TrialAccessGuard trialAccessGuard,
      UserCredentialStore userCredentialStore) {
    return request
        .bodyToMono(SlackMessageRequest.class)
        .flatMap(
            body -> {
              if (body.service() != null && !body.service().isBlank()) {
                Optional<Mono<ServerResponse>> denied =
                    trialAccessGuard.rejectUnlessServiceMatchesTrial(request, body.service());
                if (denied.isPresent()) {
                  return denied.get();
                }
              }
              String service =
                  body.service() == null || body.service().isBlank()
                      ? trialIdHeader(request)
                      : body.service().trim();
              String botToken =
                  userCredentialStore
                      .resolveSlackBotToken(service, body.botToken())
                      .orElse(body.botToken());
              if (botToken == null || botToken.isBlank()) {
                return ServerResponse.badRequest()
                    .bodyValue(Map.of("ok", false, "error", "botToken is required"));
              }
              if (body.channelId() == null || body.channelId().isBlank()) {
                return ServerResponse.badRequest()
                    .bodyValue(Map.of("ok", false, "error", "channelId is required"));
              }
              return Mono.fromCallable(
                      () ->
                          slackMessageService.sendInvestigateMessage(
                              new SlackMessageService.InvestigateSlackMessage(
                                  botToken,
                                  body.channelId(),
                                  body.title(),
                                  body.summary(),
                                  body.reasons() == null ? List.of() : body.reasons(),
                                  body.primaryFields() == null ? Map.of() : body.primaryFields(),
                                  body.flags() == null ? List.of() : body.flags(),
                                  body.link())))
                  .subscribeOn(Schedulers.boundedElastic())
                  .flatMap(
                      result ->
                          ServerResponse.ok()
                              .bodyValue(
                                  Map.of(
                                      "ok",
                                      result.ok(),
                                      "channel",
                                      result.channel(),
                                      "ts",
                                      result.ts())))
                  .onErrorResume(
                      IllegalArgumentException.class,
                      ex ->
                          ServerResponse.badRequest()
                              .bodyValue(Map.of("ok", false, "error", ex.getMessage())))
                  .onErrorResume(
                      ex ->
                          ServerResponse.status(HttpStatus.BAD_GATEWAY)
                              .bodyValue(
                                  Map.of(
                                      "ok",
                                      false,
                                      "error",
                                      ex.getMessage() == null
                                          ? "Unable to send Slack message"
                                          : ex.getMessage())));
            })
        .switchIfEmpty(
            ServerResponse.badRequest()
                .bodyValue(Map.of("ok", false, "error", "Request body is required")));
  }

  private Mono<ServerResponse> getSlackAlertConfig(
      ServerRequest request,
      PipelineAlertStore pipelineAlertStore,
      TrialAccessGuard trialAccessGuard) {
    String service = request.queryParam("service").orElse("").trim();
    if (service.isBlank()) {
      return ServerResponse.badRequest().bodyValue(Map.of("error", "service is required"));
    }
    Optional<Mono<ServerResponse>> denied =
        trialAccessGuard.rejectUnlessServiceMatchesTrial(request, service);
    if (denied.isPresent()) {
      return denied.get();
    }
    PipelineAlertStore.AlertConfigView view =
        pipelineAlertStore.toPublicView(pipelineAlertStore.getConfig(service).orElse(null));
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("service", service);
    response.put("enabled", view.enabled());
    response.put("hasBotToken", view.hasBotToken());
    response.put("channelId", view.channelId());
    response.put("channelName", view.channelName());
    response.put("configured", view.configured());
    return ServerResponse.ok().bodyValue(response);
  }

  private Mono<ServerResponse> putSlackAlertConfig(
      ServerRequest request,
      PipelineAlertStore pipelineAlertStore,
      TrialAccessGuard trialAccessGuard) {
    return request
        .bodyToMono(SlackAlertConfigRequest.class)
        .flatMap(
            body -> {
              if (body.service() == null || body.service().isBlank()) {
                return ServerResponse.badRequest().bodyValue(Map.of("error", "service is required"));
              }
              Optional<Mono<ServerResponse>> denied =
                  trialAccessGuard.rejectUnlessServiceMatchesTrial(request, body.service());
              if (denied.isPresent()) {
                return denied.get();
              }
              String existingToken =
                  pipelineAlertStore
                      .getConfig(body.service().trim())
                      .map(PipelineAlertStore.AlertConfig::botToken)
                      .orElse("");
              String botToken =
                  body.botToken() == null || body.botToken().isBlank()
                      ? existingToken
                      : body.botToken().trim();
              if (Boolean.TRUE.equals(body.enabled())
                  && (botToken == null || botToken.isBlank())) {
                return ServerResponse.badRequest()
                    .bodyValue(
                        Map.of(
                            "error",
                            "botToken is required when enabling pipeline alerts"));
              }
              if (Boolean.TRUE.equals(body.enabled())
                  && (body.channelId() == null || body.channelId().isBlank())) {
                return ServerResponse.badRequest()
                    .bodyValue(
                        Map.of(
                            "error",
                            "channelId is required when enabling pipeline alerts"));
              }
              pipelineAlertStore.saveConfig(
                  body.service().trim(),
                  new PipelineAlertStore.AlertConfig(
                      body.enabled() != null && body.enabled(),
                      botToken,
                      body.channelId() == null ? "" : body.channelId().trim(),
                      body.channelName() == null ? "" : body.channelName().trim()));
              PipelineAlertStore.AlertConfigView view =
                  pipelineAlertStore.toPublicView(
                      pipelineAlertStore.getConfig(body.service().trim()).orElse(null));
              Map<String, Object> response = new LinkedHashMap<>();
              response.put("service", body.service().trim());
              response.put("enabled", view.enabled());
              response.put("hasBotToken", view.hasBotToken());
              response.put("channelId", view.channelId());
              response.put("channelName", view.channelName());
              response.put("configured", view.configured());
              return ServerResponse.ok().bodyValue(response);
            })
        .switchIfEmpty(
            ServerResponse.badRequest().bodyValue(Map.of("error", "Request body is required")));
  }

  private static List<String> parseChannels(Object channels) {
    return SlackConnectionTestService.parseChannels(channels);
  }

  private Mono<ServerResponse> startNotionIndex(
      ServerRequest request,
      NotionIngestionJobService notionIngestionJobService,
      TrialAccessGuard trialAccessGuard,
      UserCredentialStore userCredentialStore) {
    return request
        .bodyToMono(NotionIndexRequest.class)
        .flatMap(
            body -> {
              if (body.service() == null || body.service().isBlank()) {
                return ServerResponse.badRequest().build();
              }
              Optional<Mono<ServerResponse>> denied =
                  trialAccessGuard.rejectUnlessServiceMatchesTrial(request, body.service());
              if (denied.isPresent()) {
                return denied.get();
              }
              boolean embed = body.embed() == null || body.embed();
              List<String> pageIds = NotionConnectionTestService.parseIds(body.pageIds());
              List<String> databaseIds = NotionConnectionTestService.parseIds(body.databaseIds());
              try {
                String apiKey =
                    userCredentialStore
                        .resolveNotionApiKey(body.service().trim(), body.apiKey())
                        .orElse(body.apiKey());
                if (apiKey == null || apiKey.isBlank()) {
                  return ServerResponse.badRequest()
                      .bodyValue(Map.of("error", "apiKey is required (or save it via credentials)"));
                }
                if (userCredentialStore.enabled()
                    && body.apiKey() != null
                    && !body.apiKey().isBlank()) {
                  Map<String, Object> metadata = new LinkedHashMap<>();
                  metadata.put("pageIds", pageIds);
                  metadata.put("databaseIds", databaseIds);
                  userCredentialStore.upsert(
                      body.service().trim(),
                      CredentialProviders.NOTION,
                      Map.of("apiKey", body.apiKey().trim()),
                      metadata,
                      true);
                }
                NotionIngestionJobStore.NotionIngestionJob job =
                    notionIngestionJobService.enqueue(
                        body.service().trim(),
                        apiKey,
                        pageIds,
                        databaseIds,
                        embed,
                        body.workspaceRoot());
                Map<String, Object> response =
                    Map.of("jobId", job.jobId(), "service", job.service(), "status", job.status());
                return ServerResponse.status(HttpStatus.ACCEPTED).bodyValue(response);
              } catch (IllegalArgumentException ex) {
                return ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage()));
              }
            })
        .onErrorResume(ex -> ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage())));
  }

  private Mono<ServerResponse> getNotionIndexJob(
      ServerRequest request, NotionIngestionJobService notionIngestionJobService) {
    String jobId = request.pathVariable("jobId");
    NotionIngestionJobStore.NotionIngestionJob job = notionIngestionJobService.getJob(jobId);
    if (job == null) {
      return ServerResponse.notFound().build();
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("jobId", job.jobId());
    response.put("service", job.service());
    response.put("status", job.status());
    response.put("stats", job.stats());
    response.put("error", job.error());
    return ServerResponse.ok().bodyValue(response);
  }

  private Mono<ServerResponse> testNotionConnection(
      ServerRequest request,
      NotionConnectionTestService notionConnectionTestService,
      UserCredentialStore userCredentialStore) {
    return request
        .bodyToMono(NotionTestRequest.class)
        .flatMap(
            body ->
                Mono.fromCallable(
                        () -> {
                          List<String> pageIds =
                              NotionConnectionTestService.parseIds(body.pageIds());
                          String apiKey =
                              userCredentialStore
                                  .resolveNotionApiKey(trialIdHeader(request), body.apiKey())
                                  .orElse(body.apiKey());
                          return notionConnectionTestService.test(apiKey, pageIds);
                        })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(
                        result -> {
                          Map<String, Object> response = new LinkedHashMap<>();
                          response.put("ok", result.ok());
                          response.put("workspace", result.workspace());
                          response.put("user", result.user());
                          response.put("accessiblePageIds", result.accessiblePageIds());
                          response.put("missingPageIds", result.missingPageIds());
                          return ServerResponse.ok().bodyValue(response);
                        }))
        .onErrorResume(
            IllegalArgumentException.class,
            ex -> ServerResponse.badRequest().bodyValue(Map.of("ok", false, "error", ex.getMessage())))
        .onErrorResume(
            ex ->
                ServerResponse.status(HttpStatus.BAD_GATEWAY)
                    .bodyValue(
                        Map.of(
                            "ok",
                            false,
                            "error",
                            ex.getMessage() == null ? "Notion test failed" : ex.getMessage())));
  }

  private Mono<ServerResponse> startJiraIndex(
      ServerRequest request,
      JiraIngestionJobService jiraIngestionJobService,
      TrialAccessGuard trialAccessGuard,
      UserCredentialStore userCredentialStore,
      com.andromedia.core.oauth.OAuthService oAuthService) {
    return request
        .bodyToMono(JiraIndexRequest.class)
        .flatMap(
            body -> {
              if (body.service() == null || body.service().isBlank()) {
                return ServerResponse.badRequest().build();
              }
              Optional<Mono<ServerResponse>> denied =
                  trialAccessGuard.rejectUnlessServiceMatchesTrial(request, body.service());
              if (denied.isPresent()) {
                return denied.get();
              }
              boolean embed = body.embed() == null || body.embed();
              List<String> projectKeys = JiraConnectionTestService.parseIds(body.projectKeys());
              String trialId = body.service().trim();
              try {
                oAuthService.refreshIfNeeded(
                    com.andromedia.core.oauth.OAuthProvider.JIRA, trialId);
                Optional<UserCredentialStore.JiraOAuthCredentials> credentials =
                    userCredentialStore.resolveJiraOAuthCredentials(trialId);
                if (credentials.isEmpty()) {
                  return ServerResponse.badRequest()
                      .bodyValue(
                          Map.of("error", "Connect Jira via OAuth before starting an index job"));
                }
                JiraIngestionJobStore.JiraIngestionJob job =
                    jiraIngestionJobService.enqueue(
                        trialId,
                        credentials.get().cloudId(),
                        credentials.get().accessToken(),
                        projectKeys,
                        body.lookbackDays(),
                        embed,
                        body.workspaceRoot());
                Map<String, Object> response =
                    Map.of("jobId", job.jobId(), "service", job.service(), "status", job.status());
                return ServerResponse.status(HttpStatus.ACCEPTED).bodyValue(response);
              } catch (IllegalArgumentException ex) {
                return ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage()));
              }
            })
        .onErrorResume(ex -> ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage())));
  }

  private Mono<ServerResponse> getJiraIndexJob(
      ServerRequest request, JiraIngestionJobService jiraIngestionJobService) {
    String jobId = request.pathVariable("jobId");
    JiraIngestionJobStore.JiraIngestionJob job = jiraIngestionJobService.getJob(jobId);
    if (job == null) {
      return ServerResponse.notFound().build();
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("jobId", job.jobId());
    response.put("service", job.service());
    response.put("status", job.status());
    response.put("stats", job.stats());
    response.put("error", job.error());
    return ServerResponse.ok().bodyValue(response);
  }

  private Mono<ServerResponse> testJiraConnection(
      ServerRequest request,
      JiraConnectionTestService jiraConnectionTestService,
      UserCredentialStore userCredentialStore,
      com.andromedia.core.oauth.OAuthService oAuthService) {
    return request
        .bodyToMono(JiraTestRequest.class)
        .flatMap(
            body ->
                Mono.fromCallable(
                        () -> {
                          List<String> projectKeys =
                              JiraConnectionTestService.parseIds(body.projectKeys());
                          String trialId = trialIdHeader(request);
                          oAuthService.refreshIfNeeded(
                              com.andromedia.core.oauth.OAuthProvider.JIRA, trialId);
                          Optional<UserCredentialStore.JiraOAuthCredentials> credentials =
                              userCredentialStore.resolveJiraOAuthCredentials(trialId);
                          if (credentials.isEmpty()) {
                            throw new IllegalArgumentException(
                                "Connect Jira via OAuth before testing the connection");
                          }
                          return jiraConnectionTestService.test(
                              credentials.get().cloudId(),
                              credentials.get().accessToken(),
                              projectKeys);
                        })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(
                        result -> {
                          Map<String, Object> response = new LinkedHashMap<>();
                          response.put("ok", result.ok());
                          response.put("displayName", result.displayName());
                          response.put("email", result.email());
                          response.put("accessibleProjectKeys", result.accessibleProjectKeys());
                          response.put("missingProjectKeys", result.missingProjectKeys());
                          return ServerResponse.ok().bodyValue(response);
                        }))
        .onErrorResume(
            IllegalArgumentException.class,
            ex -> ServerResponse.badRequest().bodyValue(Map.of("ok", false, "error", ex.getMessage())))
        .onErrorResume(
            ex ->
                ServerResponse.status(HttpStatus.BAD_GATEWAY)
                    .bodyValue(
                        Map.of(
                            "ok",
                            false,
                            "error",
                            ex.getMessage() == null ? "Jira test failed" : ex.getMessage())));
  }

  private Mono<ServerResponse> startLinearIndex(
      ServerRequest request,
      LinearIngestionJobService linearIngestionJobService,
      TrialAccessGuard trialAccessGuard,
      UserCredentialStore userCredentialStore) {
    return request
        .bodyToMono(LinearIndexRequest.class)
        .flatMap(
            body -> {
              if (body.service() == null || body.service().isBlank()) {
                return ServerResponse.badRequest().build();
              }
              Optional<Mono<ServerResponse>> denied =
                  trialAccessGuard.rejectUnlessServiceMatchesTrial(request, body.service());
              if (denied.isPresent()) {
                return denied.get();
              }
              boolean embed = body.embed() == null || body.embed();
              List<String> teamKeys = LinearConnectionTestService.parseIds(body.teamKeys());
              try {
                String apiKey =
                    userCredentialStore
                        .resolveLinearApiKey(body.service().trim(), body.apiKey())
                        .orElse(body.apiKey());
                if (apiKey == null || apiKey.isBlank()) {
                  return ServerResponse.badRequest()
                      .bodyValue(Map.of("error", "apiKey is required (or save it via credentials)"));
                }
                if (userCredentialStore.enabled()
                    && body.apiKey() != null
                    && !body.apiKey().isBlank()) {
                  Map<String, Object> metadata = new LinkedHashMap<>();
                  metadata.put("teamKeys", teamKeys);
                  if (body.lookbackDays() != null) {
                    metadata.put("lookbackDays", body.lookbackDays());
                  }
                  userCredentialStore.upsert(
                      body.service().trim(),
                      CredentialProviders.LINEAR,
                      Map.of("apiKey", body.apiKey().trim()),
                      metadata,
                      true);
                }
                LinearIngestionJobStore.LinearIngestionJob job =
                    linearIngestionJobService.enqueue(
                        body.service().trim(),
                        apiKey,
                        teamKeys,
                        body.lookbackDays(),
                        embed,
                        body.workspaceRoot());
                Map<String, Object> response =
                    Map.of("jobId", job.jobId(), "service", job.service(), "status", job.status());
                return ServerResponse.status(HttpStatus.ACCEPTED).bodyValue(response);
              } catch (IllegalArgumentException ex) {
                return ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage()));
              }
            })
        .onErrorResume(ex -> ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage())));
  }

  private Mono<ServerResponse> getLinearIndexJob(
      ServerRequest request, LinearIngestionJobService linearIngestionJobService) {
    String jobId = request.pathVariable("jobId");
    LinearIngestionJobStore.LinearIngestionJob job = linearIngestionJobService.getJob(jobId);
    if (job == null) {
      return ServerResponse.notFound().build();
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("jobId", job.jobId());
    response.put("service", job.service());
    response.put("status", job.status());
    response.put("stats", job.stats());
    response.put("error", job.error());
    return ServerResponse.ok().bodyValue(response);
  }

  private Mono<ServerResponse> testLinearConnection(
      ServerRequest request,
      LinearConnectionTestService linearConnectionTestService,
      UserCredentialStore userCredentialStore) {
    return request
        .bodyToMono(LinearTestRequest.class)
        .flatMap(
            body ->
                Mono.fromCallable(
                        () -> {
                          List<String> teamKeys =
                              LinearConnectionTestService.parseIds(body.teamKeys());
                          String apiKey =
                              userCredentialStore
                                  .resolveLinearApiKey(trialIdHeader(request), body.apiKey())
                                  .orElse(body.apiKey());
                          return linearConnectionTestService.test(apiKey, teamKeys);
                        })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(
                        result -> {
                          Map<String, Object> response = new LinkedHashMap<>();
                          response.put("ok", result.ok());
                          response.put("name", result.name());
                          response.put("userId", result.userId());
                          response.put("accessibleTeamKeys", result.accessibleTeamKeys());
                          response.put("missingTeamKeys", result.missingTeamKeys());
                          return ServerResponse.ok().bodyValue(response);
                        }))
        .onErrorResume(
            IllegalArgumentException.class,
            ex -> ServerResponse.badRequest().bodyValue(Map.of("ok", false, "error", ex.getMessage())))
        .onErrorResume(
            ex ->
                ServerResponse.status(HttpStatus.BAD_GATEWAY)
                    .bodyValue(
                        Map.of(
                            "ok",
                            false,
                            "error",
                            ex.getMessage() == null ? "Linear test failed" : ex.getMessage())));
  }

  private Mono<ServerResponse> startZohoIndex(
      ServerRequest request,
      ZohoIngestionJobService zohoIngestionJobService,
      TrialAccessGuard trialAccessGuard) {
    return request
        .bodyToMono(ZohoIndexRequest.class)
        .flatMap(
            body -> {
              if (body.service() == null || body.service().isBlank()) {
                return ServerResponse.badRequest().build();
              }
              Optional<Mono<ServerResponse>> denied =
                  trialAccessGuard.rejectUnlessServiceMatchesTrial(request, body.service());
              if (denied.isPresent()) {
                return denied.get();
              }
              // Match UI / andromediaApi default: omit embed => false (do not hang on embeddings).
              boolean embed = body.embed() != null && body.embed();
              try {
                ZohoIngestionJobStore.ZohoIngestionJob job;
                if (body.connectionId() != null && !body.connectionId().isBlank()) {
                  job =
                      zohoIngestionJobService.enqueueByConnectionId(
                          body.service().trim(),
                          body.connectionId().trim(),
                          body.lookbackDays(),
                          body.minQualityScore(),
                          embed,
                          body.workspaceRoot(),
                          body.fullRefresh() != null && body.fullRefresh());
                } else {
                  job =
                      zohoIngestionJobService.enqueue(
                          body.service().trim(),
                          body.clientId(),
                          body.clientSecret(),
                          body.refreshToken(),
                          body.region(),
                          body.lookbackDays(),
                          body.minQualityScore(),
                          embed,
                          body.workspaceRoot(),
                          body.fullRefresh() != null && body.fullRefresh());
                }
                Map<String, Object> response =
                    Map.of("jobId", job.jobId(), "service", job.service(), "status", job.status());
                return ServerResponse.status(HttpStatus.ACCEPTED).bodyValue(response);
              } catch (ZohoReauthRequiredException ex) {
                return zohoReauthRequired(ex);
              } catch (IllegalArgumentException ex) {
                return ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage()));
              }
            })
        .onErrorResume(
            ZohoReauthRequiredException.class, ApiRouterConfig::zohoReauthRequired)
        .onErrorResume(ex -> ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage())));
  }

  private Mono<ServerResponse> getZohoIndexJob(
      ServerRequest request, ZohoIngestionJobService zohoIngestionJobService) {
    String jobId = request.pathVariable("jobId");
    ZohoIngestionJobStore.ZohoIngestionJob job = zohoIngestionJobService.getJob(jobId);
    if (job == null) {
      return ServerResponse.notFound().build();
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("jobId", job.jobId());
    response.put("service", job.service());
    response.put("status", job.status());
    response.put("stats", job.stats());
    response.put("error", job.error());
    return ServerResponse.ok().bodyValue(response);
  }

  private Mono<ServerResponse> startGoogleChatIndex(
      ServerRequest request,
      com.andromedia.core.index.GoogleChatIngestionJobService googleChatIngestionJobService,
      TrialAccessGuard trialAccessGuard,
      UserCredentialStore userCredentialStore) {
    return request
        .bodyToMono(GoogleChatIndexRequest.class)
        .flatMap(
            body -> {
              if (body.service() == null || body.service().isBlank()) {
                return ServerResponse.badRequest().build();
              }
              Optional<Mono<ServerResponse>> denied =
                  trialAccessGuard.rejectUnlessServiceMatchesTrial(request, body.service());
              if (denied.isPresent()) {
                return denied.get();
              }
              boolean embed = body.embed() != null && body.embed();
              List<String> spaces = parseChannels(body.spaces());
              try {
                String accessToken =
                    userCredentialStore
                        .resolveGoogleChatAccessToken(body.service().trim(), body.accessToken())
                        .orElse(body.accessToken());
                if (accessToken == null || accessToken.isBlank()) {
                  return ServerResponse.badRequest()
                      .bodyValue(
                          Map.of(
                              "error",
                              "No Google Chat connection found — connect via OAuth first (see /api/v1/oauth/google_chat/authorize)"));
                }
                com.andromedia.core.index.GoogleChatIngestionJobStore.GoogleChatIngestionJob job =
                    googleChatIngestionJobService.enqueue(
                        body.service().trim(),
                        accessToken,
                        spaces,
                        embed,
                        body.lookbackDays(),
                        body.workspaceRoot());
                Map<String, Object> response =
                    Map.of("jobId", job.jobId(), "service", job.service(), "status", job.status());
                return ServerResponse.status(HttpStatus.ACCEPTED).bodyValue(response);
              } catch (IllegalArgumentException ex) {
                return ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage()));
              }
            })
        .onErrorResume(ex -> ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage())));
  }

  private Mono<ServerResponse> getGoogleChatIndexJob(
      ServerRequest request,
      com.andromedia.core.index.GoogleChatIngestionJobService googleChatIngestionJobService) {
    String jobId = request.pathVariable("jobId");
    com.andromedia.core.index.GoogleChatIngestionJobStore.GoogleChatIngestionJob job =
        googleChatIngestionJobService.getJob(jobId);
    if (job == null) {
      return ServerResponse.notFound().build();
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("jobId", job.jobId());
    response.put("service", job.service());
    response.put("status", job.status());
    response.put("stats", job.stats());
    response.put("error", job.error());
    return ServerResponse.ok().bodyValue(response);
  }

  private Mono<ServerResponse> testGoogleChatConnection(
      ServerRequest request,
      com.andromedia.core.index.GoogleChatConnectionTestService googleChatConnectionTestService,
      UserCredentialStore userCredentialStore) {
    return request
        .bodyToMono(GoogleChatTestRequest.class)
        .flatMap(
            body ->
                Mono.fromCallable(
                        () -> {
                          List<String> spaces = parseChannels(body.spaces());
                          // Unlike Slack's manual-token flow (token always comes in the request
                          // body, so trialId resolution is never actually exercised), Google Chat
                          // is OAuth-first — the token usually lives in the vault under whatever
                          // service/trialId the Connect button used ("local"), so this must
                          // resolve explicitly rather than fall back to a blank header default.
                          String service =
                              body.service() == null || body.service().isBlank()
                                  ? "local"
                                  : body.service().trim();
                          String accessToken =
                              userCredentialStore
                                  .resolveGoogleChatAccessToken(service, body.accessToken())
                                  .orElse(body.accessToken());
                          return googleChatConnectionTestService.test(accessToken, spaces);
                        })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(
                        result -> {
                          Map<String, Object> response = new LinkedHashMap<>();
                          response.put("ok", result.ok());
                          response.put("totalSpaces", result.totalSpaces());
                          response.put("resolvedSpaces", result.resolvedSpaces());
                          response.put("missingSpaces", result.missingSpaces());
                          return ServerResponse.ok().bodyValue(response);
                        }))
        .onErrorResume(
            IllegalArgumentException.class,
            ex -> ServerResponse.badRequest().bodyValue(Map.of("ok", false, "error", ex.getMessage())))
        .onErrorResume(
            ex ->
                ServerResponse.status(HttpStatus.BAD_GATEWAY)
                    .bodyValue(Map.of("ok", false, "error", ex.getMessage())));
  }

  /** Browser-navigated: redirects to the provider's consent screen. Never called via fetch(). */
  private Mono<ServerResponse> oAuthAuthorize(
      ServerRequest request, com.andromedia.core.oauth.OAuthService oAuthService) {
    com.andromedia.core.oauth.OAuthProvider provider;
    try {
      provider = com.andromedia.core.oauth.OAuthProvider.fromId(request.pathVariable("provider"));
    } catch (IllegalArgumentException ex) {
      return ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage()));
    }
    String trialId = request.queryParam("trialId").orElse("local");
    try {
      java.net.URI authorizeUrl = oAuthService.buildAuthorizeUrl(provider, trialId);
      return ServerResponse.status(org.springframework.http.HttpStatus.FOUND)
          .location(authorizeUrl)
          .build();
    } catch (com.andromedia.core.oauth.OAuthService.OAuthNotConfiguredException ex) {
      return oAuthMessagePage("Andro can't connect yet", ex.getMessage(), false);
    } catch (IllegalArgumentException ex) {
      return ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage()));
    }
  }

  /** Where the provider redirects back to after the user approves access. */
  private Mono<ServerResponse> oAuthCallback(
      ServerRequest request, com.andromedia.core.oauth.OAuthService oAuthService) {
    com.andromedia.core.oauth.OAuthProvider provider;
    try {
      provider = com.andromedia.core.oauth.OAuthProvider.fromId(request.pathVariable("provider"));
    } catch (IllegalArgumentException ex) {
      return oAuthMessagePage("Connection failed", ex.getMessage(), false);
    }
    String code = request.queryParam("code").orElse(null);
    String state = request.queryParam("state").orElse(null);
    String providerError = request.queryParam("error").orElse(null);
    if (providerError != null) {
      return oAuthMessagePage(
          "Connection cancelled", provider.id() + " reported: " + providerError, false);
    }
    com.andromedia.core.oauth.OAuthService.CallbackResult result =
        oAuthService.handleCallback(provider, code, state);
    if (result.ok()) {
      return oAuthMessagePage(
          "Connected", "Andro is now connected to " + result.label() + ". You can close this tab.", true);
    }
    return oAuthMessagePage("Connection failed", result.error(), false);
  }

  private Mono<ServerResponse> oAuthStatus(
      ServerRequest request, com.andromedia.core.oauth.OAuthService oAuthService) {
    com.andromedia.core.oauth.OAuthProvider provider;
    try {
      provider = com.andromedia.core.oauth.OAuthProvider.fromId(request.pathVariable("provider"));
    } catch (IllegalArgumentException ex) {
      return ServerResponse.badRequest().bodyValue(Map.of("error", ex.getMessage()));
    }
    String trialId = request.queryParam("trialId").orElse("local");
    com.andromedia.core.oauth.OAuthService.Status status = oAuthService.status(provider, trialId);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("provider", status.provider());
    body.put("configured", status.configured());
    body.put("connected", status.connected());
    body.put("metadata", status.metadata());
    return ServerResponse.ok().bodyValue(body);
  }

  /** A minimal, dependency-free HTML page shown in the system browser tab after OAuth completes. */
  private static Mono<ServerResponse> oAuthMessagePage(String title, String detail, boolean ok) {
    String color = ok ? "#3dd68c" : "#ef4444";
    String safeDetail = detail == null ? "" : detail.replace("<", "&lt;").replace(">", "&gt;");
    String html =
        """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <title>%s</title>
          <style>
            body { font-family: system-ui, sans-serif; background: #1c1c1c; color: #d0d0d0;
                   display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }
            .card { text-align: center; max-width: 360px; padding: 24px; }
            h1 { font-size: 16px; color: %s; margin: 0 0 8px; }
            p { font-size: 13px; color: #8a8a8a; line-height: 1.5; }
          </style>
        </head>
        <body>
          <div class="card">
            <h1>%s</h1>
            <p>%s</p>
          </div>
        </body>
        </html>
        """
            .formatted(title, color, title, safeDetail);
    return ServerResponse.ok().contentType(org.springframework.http.MediaType.TEXT_HTML).bodyValue(html);
  }

  private Mono<ServerResponse> testZohoConnection(
      ServerRequest request,
      ZohoConnectionTestService zohoConnectionTestService,
      TrialAccessGuard trialAccessGuard) {
    return request
        .bodyToMono(ZohoTestRequest.class)
        .flatMap(
            body -> {
              if (body.service() != null && !body.service().isBlank()) {
                Optional<Mono<ServerResponse>> denied =
                    trialAccessGuard.rejectUnlessServiceMatchesTrial(request, body.service());
                if (denied.isPresent()) {
                  return denied.get();
                }
              }
              return Mono.fromCallable(
                        () -> {
                          if (body.connectionId() != null && !body.connectionId().isBlank()) {
                            if (body.service() == null || body.service().isBlank()) {
                              throw new IllegalArgumentException(
                                  "service is required when using connectionId");
                            }
                            return zohoConnectionTestService.testByConnectionId(
                                body.service().trim(), body.connectionId().trim());
                          }
                          return zohoConnectionTestService.test(
                              body.clientId(),
                              body.clientSecret(),
                              body.refreshToken(),
                              body.region());
                        })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(
                        result -> {
                          Map<String, Object> response = new LinkedHashMap<>();
                          response.put("ok", result.ok());
                          response.put("region", result.region());
                          response.put("moduleCount", result.moduleCount());
                          response.put("hasDealsModule", result.hasDealsModule());
                          return ServerResponse.ok().bodyValue(response);
                        });
            })
        .onErrorResume(ZohoReauthRequiredException.class, ApiRouterConfig::zohoReauthRequired)
        .onErrorResume(
            IllegalArgumentException.class,
            ex -> ServerResponse.badRequest().bodyValue(Map.of("ok", false, "error", ex.getMessage())))
        .onErrorResume(
            ex ->
                ServerResponse.status(HttpStatus.BAD_GATEWAY)
                    .bodyValue(
                        Map.of(
                            "ok",
                            false,
                            "error",
                            ex.getMessage() == null ? "Zoho test failed" : ex.getMessage())));
  }

  private static Mono<ServerResponse> zohoReauthRequired(ZohoReauthRequiredException ex) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ok", false);
    body.put("code", ex.code());
    body.put("error", ex.getMessage());
    body.put("connectionId", ex.connectionId());
    return ServerResponse.status(HttpStatus.UNAUTHORIZED).bodyValue(body);
  }

  private static Mono<ServerResponse> zohoBadRequest(String message) {
    return ServerResponse.badRequest()
        .bodyValue(
            Map.of(
                "ok",
                false,
                "error",
                message == null || message.isBlank() ? "Invalid request" : message));
  }

  private static String requestBodyErrorMessage(Throwable ex) {
    String message = ex.getMessage() == null ? "" : ex.getMessage();
    if (message.contains("Failed to read HTTP message")
        || message.contains("JSON parse")
        || ex.getClass().getSimpleName().contains("Decoding")) {
      return "Invalid JSON request body. Send clientId, clientSecret, grantToken, and region as JSON with Content-Type: application/json";
    }
    if (message.contains("UNSUPPORTED_MEDIA_TYPE") || message.contains("Content type")) {
      return "Content-Type must be application/json";
    }
    return null;
  }

  /**
   * Retrieve, then synthesize a readable answer.
   *
   * <p>Unlike /search this spends a model call, so it consumes trial quota. The
   * earlier quota implementation only covered /investigate, which meant any
   * other model-backed route was effectively unmetered.
   */
  private Mono<ServerResponse> searchAnswer(
      ServerRequest request,
      SearchService searchService,
      ActiveZohoIndexStore activeZohoIndexStore,
      com.andromedia.core.search.SearchAnswerService searchAnswerService,
      TrialService trialService,
      TrialAccessGuard trialAccessGuard,
      ConfigurableChatService configurableChatService,
      UserCredentialStore userCredentialStore) {
    return request
        .bodyToMono(SearchApiRequest.class)
        .flatMap(
            body -> {
              if (body.service() == null || body.service().isBlank()) {
                return ServerResponse.badRequest().bodyValue(Map.of("error", "service is required"));
              }
              if (body.query() == null || body.query().isBlank()) {
                return ServerResponse.badRequest().bodyValue(Map.of("error", "query is required"));
              }
              Optional<Mono<ServerResponse>> denied =
                  trialAccessGuard.rejectUnlessServiceMatchesTrial(request, body.service());
              if (denied.isPresent()) {
                return denied.get();
              }

              if (trialService.enabled()) {
                String trialId = request.headers().firstHeader(TrialAccessGuard.TRIAL_ID_HEADER);
                if (!trialService.canQuery(trialId)) {
                  TrialSession session;
                  try {
                    session = trialService.status(trialId);
                  } catch (IllegalArgumentException ex) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("error", "Unknown or expired trial session"));
                  }
                  return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                      .bodyValue(
                          Map.of(
                              "error",
                              "Trial question limit reached",
                              "code",
                              "TRIAL_LIMIT",
                              "queriesUsed",
                              session.queriesUsed(),
                              "maxQueries",
                              session.maxQueries()));
                }
                trialService.recordQuery(trialId);
              }

              int topK = body.topK() == null || body.topK() <= 0 ? 10 : Math.min(body.topK(), 50);
              Set<String> sourceFilter = normalizeSourceTypes(body.sourceTypes());

              return Mono.fromCallable(
                      () -> {
                        List<Path> indexPaths =
                            activeZohoIndexStore.resolveIndexPaths(body.service().trim());
                        Path primaryPath = activeZohoIndexStore.resolveIndexPath(body.service().trim());
                        IndexPathHolder.setDirectIndexPaths(indexPaths);
                        try {
                          SearchResult result =
                              searchService.search(
                                  new SearchRequest(body.query().trim(), topK, SearchMode.HYBRID));
                          List<SearchHit> hits = result.hits();
                          if (!sourceFilter.isEmpty()) {
                            hits =
                                hits.stream()
                                    .filter(
                                        hit ->
                                            hit.sourceType()
                                                .map(
                                                    type ->
                                                        sourceFilter.contains(
                                                            type.toUpperCase(Locale.ROOT)))
                                                .orElse(false))
                                    .limit(topK)
                                    .toList();
                          }
                          Map<String, Object> response =
                              toSearchResponse(body.service().trim(), primaryPath, indexPaths, hits, result);
                          @SuppressWarnings("unchecked")
                          List<Map<String, Object>> hitMaps =
                              (List<Map<String, Object>>) response.get("hits");
                          // Resolve the caller's own provider key, exactly as the
                          // investigate path does. Without this the synthesis works
                          // only when a server-wide key happens to be configured,
                          // which is not how keys are stored.
                          String overrideKey =
                              resolveInvestigateLlmApiKey(
                                  request,
                                  trialService,
                                  userCredentialStore,
                                  configurableChatService,
                                  body.service().trim());
                          var answer =
                              configurableChatService.withApiKeyOverride(
                                  overrideKey,
                                  () -> searchAnswerService.answer(body.query().trim(), hitMaps));

                          Map<String, Object> payload = new LinkedHashMap<>();
                          payload.put("service", body.service().trim());
                          payload.put("query", body.query().trim());
                          payload.put("answer", answer.text());
                          payload.put("grounded", answer.grounded());
                          payload.put("citations", answer.citations());
                          payload.put("hits", hitMaps);
                          payload.put("totalHits", hitMaps.size());
                          return payload;
                        } finally {
                          IndexPathHolder.clear();
                        }
                      })
                  .subscribeOn(Schedulers.boundedElastic())
                  .flatMap(payload -> ServerResponse.ok().bodyValue(payload))
                  .onErrorResume(
                      ex ->
                          ServerResponse.badRequest()
                              .bodyValue(
                                  Map.of(
                                      "error",
                                      ex.getMessage() == null
                                          ? ex.getClass().getSimpleName()
                                          : ex.getMessage())));
            })
        .switchIfEmpty(
            ServerResponse.badRequest().bodyValue(Map.of("error", "Request body is required")));
  }

  private Mono<ServerResponse> search(
      ServerRequest request,
      SearchService searchService,
      ActiveZohoIndexStore activeZohoIndexStore,
      TrialAccessGuard trialAccessGuard) {
    return request
        .bodyToMono(SearchApiRequest.class)
        .flatMap(
            body -> {
              if (body.service() == null || body.service().isBlank()) {
                return ServerResponse.badRequest().bodyValue(Map.of("error", "service is required"));
              }
              if (body.query() == null || body.query().isBlank()) {
                return ServerResponse.badRequest().bodyValue(Map.of("error", "query is required"));
              }
              Optional<Mono<ServerResponse>> denied =
                  trialAccessGuard.rejectUnlessServiceMatchesTrial(request, body.service());
              if (denied.isPresent()) {
                return denied.get();
              }
              int topK = body.topK() == null || body.topK() <= 0 ? 10 : Math.min(body.topK(), 50);
              boolean hybrid = body.hybrid() == null || body.hybrid();
              SearchMode mode = hybrid ? SearchMode.HYBRID : SearchMode.BM25;
              Set<String> sourceFilter = normalizeSourceTypes(body.sourceTypes());

              return Mono.fromCallable(
                      () -> {
                        List<Path> indexPaths =
                            activeZohoIndexStore.resolveIndexPaths(body.service().trim());
                        Path primaryPath = activeZohoIndexStore.resolveIndexPath(body.service().trim());
                        IndexPathHolder.setDirectIndexPaths(indexPaths);
                        try {
                          SearchResult result =
                              searchService.search(new SearchRequest(body.query().trim(), topK, mode));
                          List<SearchHit> hits = result.hits();
                          if (!sourceFilter.isEmpty()) {
                            hits =
                                hits.stream()
                                    .filter(
                                        hit ->
                                            hit.sourceType()
                                                .map(type -> sourceFilter.contains(type.toUpperCase(Locale.ROOT)))
                                                .orElse(false))
                                    .limit(topK)
                                    .toList();
                          }
                          return toSearchResponse(
                              body.service().trim(), primaryPath, indexPaths, hits, result);
                        } finally {
                          IndexPathHolder.clear();
                        }
                      })
                  .subscribeOn(Schedulers.boundedElastic())
                  .flatMap(payload -> ServerResponse.ok().bodyValue(payload))
                  .onErrorResume(
                      ex ->
                          ServerResponse.badRequest()
                              .bodyValue(
                                  Map.of(
                                      "error",
                                      ex.getMessage() == null
                                          ? ex.getClass().getSimpleName()
                                          : ex.getMessage())));
            })
        .switchIfEmpty(
            ServerResponse.badRequest().bodyValue(Map.of("error", "Request body is required")));
  }

  private static Set<String> normalizeSourceTypes(List<String> sourceTypes) {
    if (sourceTypes == null || sourceTypes.isEmpty()) {
      return Set.of();
    }
    return sourceTypes.stream()
        .filter(type -> type != null && !type.isBlank())
        .map(type -> type.trim().toUpperCase(Locale.ROOT))
        .collect(Collectors.toSet());
  }

  private static Map<String, Object> toSearchResponse(
      String service,
      Path indexPath,
      List<Path> indexPaths,
      List<SearchHit> hits,
      SearchResult result) {
    List<Map<String, Object>> hitMaps = new ArrayList<>();
    for (SearchHit hit : hits) {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("path", hit.path());
      map.put("filePath", hit.path());
      map.put("fileName", hit.fileName());
      map.put("score", hit.score());
      map.put("chunkId", hit.chunkId());
      map.put("sourceType", hit.sourceType().orElse(null));
      map.put("commitSha", hit.commitSha().orElse(null));
      map.put("timestamp", hit.timestamp().orElse(null));
      map.put("content", hit.content().orElse(null));
      hit.zoho()
          .ifPresent(
              zoho -> {
                map.put("accountName", zoho.accountName().orElse(null));
                map.put("stage", zoho.stage().orElse(null));
                map.put("amount", zoho.amount().orElse(null));
                map.put("probability", zoho.probability().orElse(null));
                map.put("closingDate", zoho.closingDate().orElse(null));
                map.put("nextStep", zoho.nextStep().orElse(null));
              });
      hitMaps.add(map);
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("service", service);
    response.put("indexPath", indexPath.toString());
    response.put(
        "indexPaths",
        indexPaths == null
            ? List.of(indexPath.toString())
            : indexPaths.stream().map(Path::toString).toList());
    response.put("hits", hitMaps);
    response.put("totalHits", hitMaps.size());
    response.put(
        "elapsedMs", result.elapsed() == null ? null : result.elapsed().toMillis());
    return response;
  }

  private Mono<ServerResponse> investigate(
      ServerRequest request,
      InvestigationService investigationService,
      TrialService trialService,
      TrialAccessGuard trialAccessGuard,
      ConfigurableChatService configurableChatService,
      UserCredentialStore userCredentialStore) {
    return request
        .bodyToMono(InvestigateRequest.class)
        .flatMap(
            body -> {
              if (body.service() == null || body.service().isBlank()) {
                return ServerResponse.badRequest().build();
              }
              Optional<Mono<ServerResponse>> denied =
                  trialAccessGuard.rejectUnlessServiceMatchesTrial(request, body.service());
              if (denied.isPresent()) {
                return denied.get();
              }
              String query =
                  body.query() == null || body.query().isBlank()
                      ? "Investigate anomalies for " + body.service()
                      : body.query();

              if (trialService.enabled()) {
                String trialId = request.headers().firstHeader(TrialAccessGuard.TRIAL_ID_HEADER);
                if (!trialService.canQuery(trialId)) {
                  TrialSession session;
                  try {
                    session = trialService.status(trialId);
                  } catch (IllegalArgumentException ex) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("error", "Unknown or expired trial session"));
                  }
                  return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                      .bodyValue(
                          Map.of(
                              "error",
                              "Trial question limit reached",
                              "code",
                              "TRIAL_LIMIT",
                              "queriesUsed",
                              session.queriesUsed(),
                              "maxQueries",
                              session.maxQueries()));
                }
                trialService.recordQuery(trialId);
              }

              return Mono.fromCallable(
                      () -> {
                        String service = body.service().trim();
                        String overrideKey =
                            resolveInvestigateLlmApiKey(
                                request,
                                trialService,
                                userCredentialStore,
                                configurableChatService,
                                service);
                        return configurableChatService.withApiKeyOverride(
                            overrideKey,
                            () ->
                                investigationService.investigate(
                                    service,
                                    query,
                                    body.context() == null ? Map.of() : body.context(),
                                    InvestigationProgressSink.NOOP,
                                    body.sourceTypes() == null ? List.of() : body.sourceTypes()));
                      })
                  .subscribeOn(Schedulers.boundedElastic())
                  .flatMap(
                      result -> {
                        if (!trialService.enabled()) {
                          return ServerResponse.ok().bodyValue(result);
                        }
                        String trialId = request.headers().firstHeader(TrialAccessGuard.TRIAL_ID_HEADER);
                        int remaining = trialService.remaining(trialId);
                        Map<String, Object> metadata =
                            result.metadata() == null
                                ? new HashMap<>()
                                : new HashMap<>(result.metadata());
                        metadata.put("trialRemaining", remaining);
                        InvestigationService.InvestigateResponse enriched =
                            new InvestigationService.InvestigateResponse(
                                result.summary(),
                                result.details(),
                                metadata,
                                result.evidence(),
                                result.answer());
                        return ServerResponse.ok().bodyValue(enriched);
                      });
            })
        .onErrorResume(
            InvestigationRateLimitException.class,
            ex ->
                ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .bodyValue(
                        Map.of(
                            "error",
                            ex.getMessage() == null
                                ? "LLM rate-limited; retry shortly"
                                : ex.getMessage(),
                            "code",
                            "LLM_RATE_LIMITED")))
        .onErrorResume(
            ex -> ServerResponse.status(HttpStatus.BAD_GATEWAY).bodyValue(Map.of("error", ex.getMessage())));
  }

  private static final ObjectMapper PROGRESS_EVENT_MAPPER = new ObjectMapper();

  /**
   * Streaming twin of {@link #investigate}: same request/trial semantics, but returns
   * {@code text/event-stream} progress events as {@link InvestigationService} does each phase of
   * work, followed by a final {@code result} event (JSON-equivalent to the blocking endpoint) and
   * a {@code done} event. MCP / non-browser clients should keep using the JSON endpoint.
   */
  private Mono<ServerResponse> investigateStream(
      ServerRequest request,
      InvestigationService investigationService,
      TrialService trialService,
      TrialAccessGuard trialAccessGuard,
      ConfigurableChatService configurableChatService,
      UserCredentialStore userCredentialStore) {
    return request
        .bodyToMono(InvestigateRequest.class)
        .flatMap(
            body -> {
              if (body.service() == null || body.service().isBlank()) {
                return ServerResponse.badRequest().build();
              }
              Optional<Mono<ServerResponse>> denied =
                  trialAccessGuard.rejectUnlessServiceMatchesTrial(request, body.service());
              if (denied.isPresent()) {
                return denied.get();
              }
              String query =
                  body.query() == null || body.query().isBlank()
                      ? "Investigate anomalies for " + body.service()
                      : body.query();
              String trialId = request.headers().firstHeader(TrialAccessGuard.TRIAL_ID_HEADER);

              if (trialService.enabled()) {
                if (!trialService.canQuery(trialId)) {
                  TrialSession session;
                  try {
                    session = trialService.status(trialId);
                  } catch (IllegalArgumentException ex) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("error", "Unknown or expired trial session"));
                  }
                  return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                      .bodyValue(
                          Map.of(
                              "error",
                              "Trial question limit reached",
                              "code",
                              "TRIAL_LIMIT",
                              "queriesUsed",
                              session.queriesUsed(),
                              "maxQueries",
                              session.maxQueries()));
                }
                trialService.recordQuery(trialId);
              }

              Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
              Mono.fromRunnable(
                      () ->
                          runInvestigationStream(
                              request,
                              investigationService,
                              trialService,
                              trialId,
                              body,
                              query,
                              sink,
                              configurableChatService,
                              userCredentialStore))
                  .subscribeOn(Schedulers.boundedElastic())
                  .subscribe();

              return ServerResponse.ok()
                  .contentType(MediaType.TEXT_EVENT_STREAM)
                  .body(BodyInserters.fromPublisher(sink.asFlux(), String.class));
            });
  }

  private void runInvestigationStream(
      ServerRequest request,
      InvestigationService investigationService,
      TrialService trialService,
      String trialId,
      InvestigateRequest body,
      String query,
      Sinks.Many<String> sink,
      ConfigurableChatService configurableChatService,
      UserCredentialStore userCredentialStore) {
    try {
      String service = body.service().trim();
      String overrideKey =
          resolveInvestigateLlmApiKey(
              request,
              trialService,
              userCredentialStore,
              configurableChatService,
              service);
      InvestigationService.InvestigateResponse result =
          configurableChatService.withApiKeyOverride(
              overrideKey,
              () ->
                  investigationService.investigate(
                      service,
                      query,
                      body.context() == null ? Map.of() : body.context(),
                      event -> emitSseFrame(sink, event),
                      body.sourceTypes() == null ? List.of() : body.sourceTypes()));

      InvestigationService.InvestigateResponse enriched = result;
      if (trialService.enabled()) {
        int remaining = trialService.remaining(trialId);
        Map<String, Object> metadata =
            result.metadata() == null ? new HashMap<>() : new HashMap<>(result.metadata());
        metadata.put("trialRemaining", remaining);
        enriched =
            new InvestigationService.InvestigateResponse(
                result.summary(), result.details(), metadata, result.evidence(), result.answer());
      }
      emitSseFrame(sink, InvestigationProgressEvent.result(enriched));
      emitSseFrame(sink, InvestigationProgressEvent.done());
    } catch (InvestigationRateLimitException ex) {
      emitSseFrame(
          sink,
          InvestigationProgressEvent.error(
              "LLM_RATE_LIMITED",
              ex.getMessage() == null ? "LLM rate-limited; retry shortly" : ex.getMessage()));
    } catch (Exception ex) {
      emitSseFrame(
          sink,
          InvestigationProgressEvent.error(
              "INVESTIGATE_FAILED",
              ex.getMessage() == null ? "Investigation failed" : ex.getMessage()));
    } finally {
      sink.tryEmitComplete();
    }
  }

  /**
   * Emits the raw JSON payload only — WebFlux's {@code text/event-stream} writer already adds
   * the {@code data:} prefix and blank-line frame separator for plain (non-{@code
   * ServerSentEvent}) body elements, so adding them here would double-wrap the frame.
   */
  private static void emitSseFrame(Sinks.Many<String> sink, InvestigationProgressEvent event) {
    try {
      String json = PROGRESS_EVENT_MAPPER.writeValueAsString(event);
      sink.tryEmitNext(json);
    } catch (Exception ignored) {
      // best-effort; drop a malformed progress frame rather than failing the whole stream
    }
  }

  public record IndexRequest(
      String service,
      String repo,
      String branch,
      String localPath,
      String repoSubpath,
      Boolean embed) {}

  public record HistoryIndexRequest(
      String service, String localPath, Boolean embed, Integer maxCommits, String since) {}

  public record SlackIndexRequest(
      String service,
      String botToken,
      Object channels,
      Boolean embed,
      Integer lookbackDays,
      String workspaceRoot) {}

  public record SlackTestRequest(String botToken, Object channels) {}

  public record GoogleChatIndexRequest(
      String service,
      String accessToken,
      Object spaces,
      Boolean embed,
      Integer lookbackDays,
      String workspaceRoot) {}

  public record GoogleChatTestRequest(String service, String accessToken, Object spaces) {}

  public record SlackChannelsRequest(String botToken) {}

  public record SlackMessageRequest(
      String service,
      String botToken,
      String channelId,
      String title,
      String summary,
      List<String> reasons,
      Map<String, String> primaryFields,
      List<String> flags,
      String link) {}

  public record SlackAlertConfigRequest(
      String service,
      Boolean enabled,
      String botToken,
      String channelId,
      String channelName) {}

  public record NotionIndexRequest(
      String service,
      String apiKey,
      Object pageIds,
      Object databaseIds,
      Boolean embed,
      String workspaceRoot) {}

  public record NotionTestRequest(String apiKey, Object pageIds) {}

  public record JiraIndexRequest(
      String service,
      Object projectKeys,
      Integer lookbackDays,
      Boolean embed,
      String workspaceRoot) {}

  public record JiraTestRequest(Object projectKeys) {}

  public record LinearIndexRequest(
      String service,
      String apiKey,
      Object teamKeys,
      Integer lookbackDays,
      Boolean embed,
      String workspaceRoot) {}

  public record LinearTestRequest(String apiKey, Object teamKeys) {}

  public record ZohoIndexRequest(
      String service,
      String connectionId,
      String clientId,
      String clientSecret,
      String refreshToken,
      String region,
      Integer lookbackDays,
      Double minQualityScore,
      Boolean embed,
      String workspaceRoot,
      Boolean fullRefresh) {}

  public record ZohoTestRequest(
      String service,
      String connectionId,
      String clientId,
      String clientSecret,
      String refreshToken,
      String region) {}

  public record InvestigateRequest(
      String service, String query, Map<String, Object> context, List<String> sourceTypes) {}

  public record SearchApiRequest(
      String service,
      String query,
      Integer topK,
      Boolean hybrid,
      List<String> sourceTypes) {}

  public record ChatProviderRequest(String provider, String model, String apiKey) {}

  public record TrialStartRequest(String trialId) {}
}
