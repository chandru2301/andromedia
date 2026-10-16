package com.andromedia.search;

import com.andromedia.common.IndexConstants;
import com.andromedia.llm.EmbeddingException;
import com.andromedia.llm.EmbeddingService;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VectorSearchService {

  private final String configuredIndexPath;
  private final EmbeddingService embeddingService;
  private final HeuristicRanker heuristicRanker;
  private final QueryTypeClassifier queryTypeClassifier;

  @Autowired
  public VectorSearchService(
      @Value("${" + IndexConstants.INDEX_PATH_PROPERTY + ":}") String configuredIndexPath,
      EmbeddingService embeddingService,
      HeuristicRanker heuristicRanker,
      QueryTypeClassifier queryTypeClassifier) {
    this.configuredIndexPath = configuredIndexPath;
    this.embeddingService = embeddingService;
    this.heuristicRanker = heuristicRanker;
    this.queryTypeClassifier = queryTypeClassifier;
  }

  public VectorSearchService(String configuredIndexPath, EmbeddingService embeddingService) {
    this(
        configuredIndexPath,
        embeddingService,
        new HeuristicRanker(new RankingHeuristicsProperties(), new QueryTypeClassifier()),
        new QueryTypeClassifier());
  }

  public SearchResult search(SearchRequest request) {
    Instant startedAt = Instant.now();
    Path workspaceRoot = IndexAccess.resolveWorkspaceRoot();
    List<Path> projectIndexPaths = IndexAccess.resolveProjectIndexPaths(configuredIndexPath);
    IndexAccess.validateAnyIndexExists(projectIndexPaths);
    Path projectIndexPath = projectIndexPaths.getFirst();
    if (!IndexAccess.hasEmbeddingField(projectIndexPaths)) {
      throw new SearchException(
          "No embeddings in index at "
              + projectIndexPath
              + ". Re-index with `andro index --embed <path>`.");
    }
    QueryType queryType = queryTypeClassifier.classify(request.query());

    float[] queryVector = embedQuery(request.query());
    int maxResults = Math.max(1, request.maxResults());

    try (IndexAccess.OpenedIndex opened = IndexAccess.openIndex(projectIndexPaths)) {
      IndexSearcher searcher = new IndexSearcher(opened.reader());
      KnnSearchResult knnResult = knnSearch(searcher, queryVector, maxResults);

      List<SearchHit> knnHits = AndroSlackSelfPostFilter.remove(knnResult.hits());
      int beforeFilter = knnHits.size();
      List<SearchHit> filtered = heuristicRanker.filterSemanticByThreshold(knnHits);
      int filteredCount = beforeFilter - filtered.size();
      List<SearchHit> ranked = heuristicRanker.rank(request.query(), queryType, filtered);

      Duration elapsed = Duration.between(startedAt, Instant.now());
      SearchDiagnostics diagnostics =
          new SearchDiagnostics(
              request.query(),
              workspaceRoot.toString(),
              projectIndexPaths.stream().map(Path::toString).reduce((a, b) -> a + "," + b).orElse(""),
              null,
              null,
              null,
              queryType,
              null,
              null,
              filteredCount,
              null);
      return new SearchResult(ranked, knnResult.totalHits(), elapsed, diagnostics);
    } catch (IOException ex) {
      throw new SearchException(
          "Failed to run semantic search at: "
              + projectIndexPath
              + " (workspace root: "
              + workspaceRoot
              + ")",
          ex);
    }
  }

  List<SearchHit> searchRaw(SearchRequest request) {
    List<Path> projectIndexPaths = IndexAccess.resolveProjectIndexPaths(configuredIndexPath);
    IndexAccess.validateAnyIndexExists(projectIndexPaths);
    Path projectIndexPath = projectIndexPaths.getFirst();
    if (!IndexAccess.hasEmbeddingField(projectIndexPaths)) {
      throw new SearchException(
          "No embeddings in index at "
              + projectIndexPath
              + ". Re-index with `andro index --embed <path>`.");
    }

    float[] queryVector = embedQuery(request.query());
    int maxResults = Math.max(1, request.maxResults());

    try (IndexAccess.OpenedIndex opened = IndexAccess.openIndex(projectIndexPaths)) {
      IndexSearcher searcher = new IndexSearcher(opened.reader());
      return AndroSlackSelfPostFilter.remove(knnSearch(searcher, queryVector, maxResults).hits());
    } catch (IOException ex) {
      throw new SearchException("Failed to run semantic search at: " + projectIndexPath, ex);
    }
  }

  private float[] embedQuery(String query) {
    try {
      return embeddingService.embed(query);
    } catch (EmbeddingException ex) {
      throw new SearchException(
          "Query embedding failed (check network/DNS and OPENROUTER_API_KEY): " + ex.getMessage(),
          ex);
    }
  }

  private static KnnSearchResult knnSearch(IndexSearcher searcher, float[] queryVector, int maxResults)
      throws IOException {
    KnnFloatVectorQuery vectorQuery =
        new KnnFloatVectorQuery(IndexConstants.FIELD_EMBEDDING, queryVector, maxResults);
    TopDocs topDocs = searcher.search(vectorQuery, maxResults);

    List<SearchHit> hits = new ArrayList<>();
    for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
      var document = searcher.storedFields().document(scoreDoc.doc);
      SearchHit hit = IndexDocumentMapper.toSearchHit(document, scoreDoc.score);
      hits.add(
          new SearchHit(
              hit.path(),
              hit.fileName(),
              hit.score(),
              hit.chunkId(),
              hit.className(),
              hit.methodName(),
              hit.unitType(),
              hit.startLine(),
              hit.endLine(),
              hit.bm25Score(),
              scoreDoc.score,
              hit.imports(),
              hit.symbolIds(),
              hit.symbolNames(),
              hit.sourceType(),
              hit.commitSha(),
              hit.timestamp(),
              hit.content(),
              hit.zoho()));
    }
    return new KnnSearchResult(hits, topDocs.totalHits.value);
  }

  private record KnnSearchResult(List<SearchHit> hits, long totalHits) {}
}
