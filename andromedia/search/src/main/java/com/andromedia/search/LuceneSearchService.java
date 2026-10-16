package com.andromedia.search;

import com.andromedia.common.IndexConstants;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LuceneSearchService implements SearchService {

  private final String configuredIndexPath;
  private final HeuristicRanker heuristicRanker;
  private final QueryTypeClassifier queryTypeClassifier;

  @Autowired
  public LuceneSearchService(
      @Value("${" + IndexConstants.INDEX_PATH_PROPERTY + ":}") String configuredIndexPath,
      HeuristicRanker heuristicRanker,
      QueryTypeClassifier queryTypeClassifier) {
    this.configuredIndexPath = configuredIndexPath;
    this.heuristicRanker = heuristicRanker;
    this.queryTypeClassifier = queryTypeClassifier;
  }

  public LuceneSearchService(String configuredIndexPath) {
    this(
        configuredIndexPath,
        new HeuristicRanker(new RankingHeuristicsProperties(), new QueryTypeClassifier()),
        new QueryTypeClassifier());
  }

  @Override
  public SearchResult search(SearchRequest request) {
    Instant startedAt = Instant.now();
    Path workspaceRoot = IndexAccess.resolveWorkspaceRoot();
    List<Path> projectIndexPaths = IndexAccess.resolveProjectIndexPaths(configuredIndexPath);
    IndexAccess.validateAnyIndexExists(projectIndexPaths);
    Path projectIndexPath = projectIndexPaths.getFirst();
    QueryType queryType = queryTypeClassifier.classify(request.query());

    try (IndexAccess.OpenedIndex opened = IndexAccess.openIndex(projectIndexPaths)) {
      IndexSearcher searcher = new IndexSearcher(opened.reader());
      Query query = parseQuery(request.query());
      int maxResults = Math.max(1, request.maxResults());
      TopDocs topDocs = searcher.search(query, maxResults);

      List<SearchHit> hits = new ArrayList<>();
      for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
        var document = searcher.storedFields().document(scoreDoc.doc);
        hits.add(IndexDocumentMapper.toSearchHit(document, scoreDoc.score));
      }
      hits = AndroSlackSelfPostFilter.remove(hits);

      List<SearchHit> ranked = heuristicRanker.rank(request.query(), queryType, hits);

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
              null,
              null);
      return new SearchResult(ranked, topDocs.totalHits.value, elapsed, diagnostics);
    } catch (IOException ex) {
      throw new SearchException(
          "Failed to search index at: " + projectIndexPath + " (workspace root: " + workspaceRoot + ")",
          ex);
    }
  }

  List<SearchHit> searchRaw(SearchRequest request) {
    List<Path> projectIndexPaths = IndexAccess.resolveProjectIndexPaths(configuredIndexPath);
    IndexAccess.validateAnyIndexExists(projectIndexPaths);
    Path projectIndexPath = projectIndexPaths.getFirst();

    try (IndexAccess.OpenedIndex opened = IndexAccess.openIndex(projectIndexPaths)) {
      IndexSearcher searcher = new IndexSearcher(opened.reader());
      Query query = parseQuery(request.query());
      int maxResults = Math.max(1, request.maxResults());
      TopDocs topDocs = searcher.search(query, maxResults);

      List<SearchHit> hits = new ArrayList<>();
      for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
        var document = searcher.storedFields().document(scoreDoc.doc);
        hits.add(IndexDocumentMapper.toSearchHit(document, scoreDoc.score));
      }
      return AndroSlackSelfPostFilter.remove(hits);
    } catch (IOException ex) {
      throw new SearchException("Failed to search index at: " + projectIndexPath, ex);
    }
  }

  private Query parseQuery(String queryText) {
    String effectiveQuery = prepareQueryText(queryText);
    QueryParser parser =
        new QueryParser(IndexConstants.FIELD_CONTENT, new StandardAnalyzer());
    parser.setDefaultOperator(operatorFor(queryText));
    try {
      return parser.parse(effectiveQuery);
    } catch (ParseException ex) {
      throw new SearchException("Invalid search query: " + queryText, ex);
    }
  }

  /**
   * Strips history then decision filler terms so BM25 matches substantive tokens in prose
   * (Slack threads, commit messages). Falls back to the original query if sanitization empties it.
   */
  static String prepareQueryText(String queryText) {
    if (queryText == null || queryText.isBlank()) {
      return queryText;
    }
    String effective = queryText;
    if (HistoryIntentDetector.isHistoryIntent(queryText)) {
      effective = HistoryIntentDetector.sanitizeForRetrieval(effective);
    }
    if (DecisionIntentDetector.isDecisionIntent(queryText)) {
      effective = DecisionIntentDetector.sanitizeForRetrieval(effective);
    }
    if (effective == null || effective.isBlank()) {
      return queryText;
    }
    return effective;
  }

  /**
   * Identifier queries stay strict (AND). Natural-language, decision, and history intents use OR
   * so Slack/git prose can match on any substantive term.
   */
  static QueryParser.Operator operatorFor(String queryText) {
    QueryType queryType = new QueryTypeClassifier().classify(queryText);
    if (queryType == QueryType.NATURAL_LANGUAGE
        || DecisionIntentDetector.isDecisionIntent(queryText)
        || HistoryIntentDetector.isHistoryIntent(queryText)) {
      return QueryParser.Operator.OR;
    }
    return QueryParser.Operator.AND;
  }
}
