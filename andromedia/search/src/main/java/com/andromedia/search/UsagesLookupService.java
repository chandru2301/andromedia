package com.andromedia.search;

import com.andromedia.common.ChunkUnitType;
import com.andromedia.common.IndexConstants;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class UsagesLookupService {

  private final String configuredIndexPath;

  @Autowired
  public UsagesLookupService(
      @Value("${" + IndexConstants.INDEX_PATH_PROPERTY + ":}") String configuredIndexPath) {
    this.configuredIndexPath = configuredIndexPath;
  }

  public static UsagesLookupService forTest(String configuredIndexPath) {
    return new UsagesLookupService(configuredIndexPath, true);
  }

  private UsagesLookupService(String configuredIndexPath, boolean testOnly) {
    this.configuredIndexPath = configuredIndexPath;
  }

  public UsageLookupResult lookup(UsageLookupRequest request) {
    Instant startedAt = Instant.now();
    Path projectIndexPath = IndexAccess.resolveProjectIndexPath(configuredIndexPath);
    IndexAccess.validateIndexExists(projectIndexPath);

    LuceneSymbolIndex symbolIndex = LuceneSymbolIndex.load(projectIndexPath);
    List<String> resolvedSymbolIds =
        SymbolQueryParser.resolveSymbolIdsFromIndex(request.symbolQuery(), symbolIndex);
    if (resolvedSymbolIds.isEmpty()) {
      List<MethodHint> methodHints =
          SymbolQueryParser.methodHintsForMissedClassMethodQuery(request.symbolQuery(), symbolIndex);
      return new UsageLookupResult(List.of(), List.of(), List.of(), elapsed(startedAt), methodHints);
    }

    int limit = Math.max(1, request.maxResults());
    List<UsageSite> definitions =
        request.includeDefinitions()
            ? lookupSites(projectIndexPath, IndexConstants.FIELD_SYMBOL_IDS, resolvedSymbolIds, limit, UsageRole.DEFINITION)
            : List.of();
    List<UsageSite> references =
        request.includeReferences()
            ? lookupSites(
                projectIndexPath,
                IndexConstants.FIELD_REFERENCED_SYMBOL_IDS,
                resolvedSymbolIds,
                limit,
                UsageRole.REFERENCE)
            : List.of();

    return new UsageLookupResult(definitions, references, resolvedSymbolIds, elapsed(startedAt));
  }

  private static List<UsageSite> lookupSites(
      Path projectIndexPath,
      String field,
      List<String> symbolIds,
      int limit,
      UsageRole role) {
    Map<String, UsageSite> deduped = new LinkedHashMap<>();
    try (Directory directory = FSDirectory.open(projectIndexPath);
        DirectoryReader reader = DirectoryReader.open(directory)) {
      IndexSearcher searcher = new IndexSearcher(reader);
      for (String symbolId : symbolIds) {
        TopDocs topDocs = searcher.search(new TermQuery(new Term(field, symbolId)), limit);
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
          var document = searcher.storedFields().document(scoreDoc.doc);
          UsageSite site = IndexDocumentMapper.toUsageSite(document, role, symbolId);
          deduped.putIfAbsent(site.chunkId(), site);
          if (deduped.size() >= limit) {
            break;
          }
        }
        if (deduped.size() >= limit) {
          break;
        }
      }
    } catch (IOException ex) {
      throw new SearchException("Failed usages lookup at: " + projectIndexPath, ex);
    }
    List<UsageSite> sites = new ArrayList<>(deduped.values());
    sites.sort(
        Comparator.comparing(UsageSite::path)
            .thenComparing(UsageSite::startLine)
            .thenComparing(UsageSite::fileName));
    return sites;
  }

  private static Duration elapsed(Instant startedAt) {
    return Duration.between(startedAt, Instant.now());
  }
}
