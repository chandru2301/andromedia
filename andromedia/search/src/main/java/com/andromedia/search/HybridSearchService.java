package com.andromedia.search;

import com.andromedia.common.IndexConstants;
import com.andromedia.common.IndexPathHolder;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HybridSearchService {

  private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);
  private static final int CANDIDATE_MULTIPLIER = 5;
  private static final int MIN_CANDIDATE_POOL = 50;
  static final String SEMANTIC_EMBEDDING_FAILURE_WARNING =
      "Warning: semantic channel unavailable (query embedding failed); using BM25 only.";

  private final String configuredIndexPath;
  private final LuceneSearchService bm25SearchService;
  private final VectorSearchService vectorSearchService;
  private final Reranker reranker;
  private final HeuristicRanker heuristicRanker;
  private final QueryTypeClassifier queryTypeClassifier;
  private final RankingHeuristicsProperties rankingProperties;

  @Autowired
  public HybridSearchService(
      @Value("${" + IndexConstants.INDEX_PATH_PROPERTY + ":}") String configuredIndexPath,
      LuceneSearchService bm25SearchService,
      VectorSearchService vectorSearchService,
      Reranker reranker,
      HeuristicRanker heuristicRanker,
      QueryTypeClassifier queryTypeClassifier,
      RankingHeuristicsProperties rankingProperties) {
    this.configuredIndexPath = configuredIndexPath;
    this.bm25SearchService = bm25SearchService;
    this.vectorSearchService = vectorSearchService;
    this.reranker = reranker;
    this.heuristicRanker = heuristicRanker;
    this.queryTypeClassifier = queryTypeClassifier;
    this.rankingProperties = rankingProperties;
  }

  public HybridSearchService(
      String configuredIndexPath,
      LuceneSearchService bm25SearchService,
      VectorSearchService vectorSearchService,
      Reranker reranker) {
    this(
        configuredIndexPath,
        bm25SearchService,
        vectorSearchService,
        reranker,
        new HeuristicRanker(new RankingHeuristicsProperties(), new QueryTypeClassifier()),
        new QueryTypeClassifier(),
        new RankingHeuristicsProperties());
  }

  public SearchResult search(SearchRequest request) {
    Instant startedAt = Instant.now();
    Path workspaceRoot = IndexAccess.resolveWorkspaceRoot();
    List<Path> projectIndexPaths = IndexAccess.resolveProjectIndexPaths(configuredIndexPath);
    IndexAccess.validateAnyIndexExists(projectIndexPaths);
    Path projectIndexPath = projectIndexPaths.getFirst();
    QueryType queryType = queryTypeClassifier.classify(request.query());
    RankingHeuristicsProperties.ChannelWeights channelWeights =
        resolveChannelWeights(queryType, projectIndexPaths);

    int maxResults = Math.max(1, request.maxResults());
    int candidatePool = Math.max(maxResults * CANDIDATE_MULTIPLIER, MIN_CANDIDATE_POOL);
    // Fuse/repair on a larger pool so BM25-strong / KNN-weak entity hits survive; truncate last.
    int fusePool = Math.min(Math.max(maxResults * 4, 25), candidatePool);
    SearchRequest candidateRequest =
        new SearchRequest(request.query(), candidatePool, SearchMode.BM25);

    // Capture paths on the caller thread — IndexPathHolder is ThreadLocal and async workers
    // otherwise fall back to the wrong (project) index during /investigate.
    List<Path> indexPathsForWorkers = List.copyOf(projectIndexPaths);
    CompletableFuture<List<SearchHit>> bm25Future =
        CompletableFuture.supplyAsync(
            () ->
                withIndexPaths(
                    indexPathsForWorkers, () -> bm25SearchService.searchRaw(candidateRequest)));
    CompletableFuture<SemanticCandidateResult> semanticFuture =
        CompletableFuture.supplyAsync(
            () ->
                withIndexPaths(
                    indexPathsForWorkers, () -> loadSemanticCandidates(candidateRequest)));

    List<SearchHit> bm25Hits;
    SemanticCandidateResult semanticResult;
    try {
      bm25Hits = AndroSlackSelfPostFilter.remove(bm25Future.get());
      semanticResult = semanticFuture.get();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new SearchException("Hybrid search interrupted", ex);
    } catch (ExecutionException ex) {
      throw new SearchException("Hybrid search failed", ex.getCause());
    }

    if (semanticResult.embeddingFailure()) {
      System.err.println(SEMANTIC_EMBEDDING_FAILURE_WARNING);
    }

    List<SearchHit> semanticHits = AndroSlackSelfPostFilter.remove(semanticResult.hits());
    int semanticBeforeFilter = semanticHits.size();
    List<SearchHit> semanticFiltered = heuristicRanker.filterSemanticByThreshold(semanticHits);
    int semanticFilteredCount = semanticBeforeFilter - semanticFiltered.size();

    List<SearchHit> fused =
        ReciprocalRankFusion.fuse(
            bm25Hits,
            semanticFiltered,
            fusePool,
            ReciprocalRankFusion.DEFAULT_K,
            channelWeights);
    List<SearchHit> covered =
        SourceCoverageMerger.merge(
            fused, bm25Hits, semanticFiltered, fusePool, request.query());
    List<SearchHit> relevanceFiltered = RelativeScoreFilter.apply(covered, fusePool);
    // Anchor against heuristically ranked BM25 so Slack/decision boosts match what BM25-only
    // search surfaces (raw Lucene scores alone under-trigger the strong-anchor floor).
    List<SearchHit> bm25Anchors = heuristicRanker.rank(request.query(), queryType, bm25Hits);
    List<SearchHit> lexicallyAnchored =
        LexicalAnchorFilter.apply(relevanceFiltered, bm25Anchors, fusePool, request.query());
    List<SearchHit> truncated =
        lexicallyAnchored.size() <= maxResults
            ? lexicallyAnchored
            : lexicallyAnchored.stream().limit(maxResults).toList();
    List<SearchHit> reranked = reranker.rerank(request.query(), truncated);
    List<SearchHit> ranked = heuristicRanker.rank(request.query(), queryType, reranked);

    Duration elapsed = Duration.between(startedAt, Instant.now());
    SearchDiagnostics diagnostics =
        new SearchDiagnostics(
            request.query(),
            workspaceRoot.toString(),
            projectIndexPath.toString(),
            bm25Hits.size(),
            semanticFiltered.size(),
            semanticResult.skipped(),
            queryType,
            channelWeights.bm25Weight(),
            channelWeights.semanticWeight(),
            semanticFilteredCount,
            semanticResult.skipReason());
    long totalHits = bm25Hits.size() + semanticFiltered.size();
    return new SearchResult(ranked, totalHits, elapsed, diagnostics);
  }

  /**
   * When only some index paths have embeddings (e.g. Zoho sidecar yes, Notion base no), NL weights
   * (0.6/1.0) let KNN drown BM25-only sources — fall back to MIXED (0.85/0.85).
   */
  RankingHeuristicsProperties.ChannelWeights resolveChannelWeights(
      QueryType queryType, List<Path> projectIndexPaths) {
    RankingHeuristicsProperties.ChannelWeights base = rankingProperties.channelWeightsFor(queryType);
    if (queryType == QueryType.NATURAL_LANGUAGE
        && IndexAccess.embeddingCoverage(projectIndexPaths) == IndexAccess.EmbeddingCoverage.PARTIAL) {
      log.debug(
          "Hybrid search: partial embedding coverage across {} — using MIXED channel weights",
          projectIndexPaths);
      return rankingProperties.channelWeightsFor(QueryType.MIXED);
    }
    return base;
  }

  private SemanticCandidateResult loadSemanticCandidates(SearchRequest candidateRequest) {
    List<Path> projectIndexPaths = IndexAccess.resolveProjectIndexPaths(configuredIndexPath);
    if (!IndexAccess.hasEmbeddingField(projectIndexPaths)) {
      log.warn(
          "Hybrid search: no embeddings in index at {} — using BM25 channel only",
          projectIndexPaths);
      return new SemanticCandidateResult(
          List.of(), true, "index has no embeddings", false);
    }

    SearchRequest semanticRequest =
        new SearchRequest(
            candidateRequest.query(), candidateRequest.maxResults(), SearchMode.SEMANTIC);
    try {
      List<SearchHit> raw = vectorSearchService.searchRaw(semanticRequest);
      List<SearchHit> withSemanticScores = new ArrayList<>(raw.size());
      for (SearchHit hit : raw) {
        float semanticScore = hit.semanticScore() != null ? hit.semanticScore() : hit.score();
        withSemanticScores.add(
            new SearchHit(
                hit.path(),
                hit.fileName(),
                semanticScore,
                hit.chunkId(),
                hit.className(),
                hit.methodName(),
                hit.unitType(),
                hit.startLine(),
                hit.endLine(),
                hit.bm25Score(),
                semanticScore,
                hit.imports(),
                hit.symbolIds(),
                hit.symbolNames(),
                hit.sourceType(),
                hit.commitSha(),
                hit.timestamp(),
                hit.content(),
                hit.zoho()));
      }
      return new SemanticCandidateResult(withSemanticScores, false, null, false);
    } catch (SearchException ex) {
      boolean embeddingFailure = isEmbeddingFailure(ex);
      log.warn(
          "Hybrid search: semantic channel failed — {}{}",
          ex.getMessage(),
          rootCauseMessage(ex));
      return new SemanticCandidateResult(
          List.of(),
          true,
          ex.getMessage(),
          embeddingFailure);
    }
  }

  private static boolean isEmbeddingFailure(SearchException ex) {
    String message = ex.getMessage();
    return message != null && message.startsWith("Query embedding failed");
  }

  private static String rootCauseMessage(Throwable throwable) {
    Throwable cause = throwable.getCause();
    if (cause == null || cause.getMessage() == null) {
      return "";
    }
    return " (" + cause.getMessage() + ")";
  }

  private static <T> T withIndexPaths(List<Path> indexPaths, Supplier<T> action) {
    IndexPathHolder.setDirectIndexPaths(indexPaths);
    try {
      return action.get();
    } finally {
      IndexPathHolder.clear();
    }
  }

  record SemanticCandidateResult(
      List<SearchHit> hits, boolean skipped, String skipReason, boolean embeddingFailure) {}
}
