package com.andromedia.search;

import com.andromedia.common.IndexConstants;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class HeuristicRanker {

  private final RankingHeuristicsProperties properties;
  private final QueryTypeClassifier queryTypeClassifier;

  public HeuristicRanker(
      RankingHeuristicsProperties properties, QueryTypeClassifier queryTypeClassifier) {
    this.properties = properties;
    this.queryTypeClassifier = queryTypeClassifier;
  }

  public List<SearchHit> rank(String query, List<SearchHit> hits) {
    QueryType queryType = queryTypeClassifier.classify(query);
    return rank(query, queryType, hits);
  }

  public List<SearchHit> rank(String query, QueryType queryType, List<SearchHit> hits) {
    if (hits == null || hits.isEmpty()) {
      return List.of();
    }
    return hits.stream()
        .map(hit -> applyHeuristics(query, queryType, hit))
        .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
        .toList();
  }

  public List<SearchHit> filterSemanticByThreshold(List<SearchHit> hits) {
    double minScore = properties.semanticMinScore();
    return hits.stream()
        .filter(
            hit -> {
              if (hit.semanticScore() == null) {
                return hit.score() >= minScore;
              }
              return hit.semanticScore() >= minScore;
            })
        .toList();
  }

  private SearchHit applyHeuristics(String query, QueryType queryType, SearchHit hit) {
    double factor = 1.0;
    factor *= pathFactor(query, hit.path());
    factor *= metadataFactor(hit);
    factor *= exactMatchFactor(query, queryType, hit);
    if (HistoryIntentDetector.isHistoryIntent(query)
        && hit.sourceType().orElse("").equals(IndexConstants.SOURCE_TYPE_GIT)) {
      factor *= properties.gitHistoryBoost();
    }
    if (DecisionIntentDetector.isDecisionIntent(query)
        && hit.sourceType().orElse("").equals(IndexConstants.SOURCE_TYPE_SLACK)) {
      factor *= properties.slackDecisionBoost();
      factor *= reactionBoost(hit);
    }
    if (ArchitectureIntentDetector.isArchitectureIntent(query)
        && hit.sourceType().orElse("").equals(IndexConstants.SOURCE_TYPE_NOTION)) {
      factor *= properties.notionArchitectureBoost();
    }
    if (TicketIntentDetector.isTicketIntent(query)) {
      String source = hit.sourceType().orElse("");
      if (IndexConstants.SOURCE_TYPE_JIRA.equals(source)) {
        factor *= properties.jiraTicketBoost();
      } else if (IndexConstants.SOURCE_TYPE_LINEAR.equals(source)) {
        factor *= properties.linearTicketBoost();
      } else if (IndexConstants.SOURCE_TYPE_ZOHO.equals(source)) {
        factor *= properties.zohoCrmBoost();
      }
    }
    return hit.withScore((float) (hit.score() * factor));
  }

  private static double reactionBoost(SearchHit hit) {
    String content = hit.content().orElse("");
    int marker = content.indexOf("reactions: ");
    if (marker < 0) {
      return 1.0;
    }
    int start = marker + "reactions: ".length();
    int end = start;
    while (end < content.length() && Character.isDigit(content.charAt(end))) {
      end++;
    }
    if (end == start) {
      return 1.0;
    }
    try {
      int reactions = Integer.parseInt(content.substring(start, end));
      return 1.0 + Math.min(20, Math.max(0, reactions)) * 0.05;
    } catch (NumberFormatException ex) {
      return 1.0;
    }
  }

  private double pathFactor(String query, String path) {
    if (path == null || path.isBlank()) {
      return 1.0;
    }
    String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
    if (isTestPath(normalized)) {
      return HistoryIntentDetector.isHistoryIntent(query)
          ? properties.testPenalty() * 0.25
          : properties.testPenalty();
    }
    if (isGeneratedOrBuildPath(normalized)) {
      return properties.generatedPenalty();
    }
    if (isConfigPath(normalized)) {
      return properties.configPenalty();
    }
    if (normalized.contains("/src/main/") || normalized.contains("/main/")) {
      return properties.mainSourceBoost();
    }
    return 1.0;
  }

  private double metadataFactor(SearchHit hit) {
    String fileName = hit.fileName() == null ? "" : hit.fileName().toLowerCase(Locale.ROOT);
    String className = hit.className() == null ? "" : hit.className().toLowerCase(Locale.ROOT);
    String methodName = hit.methodName() == null ? "" : hit.methodName().toLowerCase(Locale.ROOT);
    String combined = fileName + " " + className + " " + methodName;
    if (combined.contains("service")
        || combined.contains("listener")
        || combined.contains("consumer")
        || combined.contains("handler")
        || combined.contains("controller")) {
      return properties.serviceListenerConsumerBoost();
    }
    return 1.0;
  }

  private double exactMatchFactor(String query, QueryType queryType, SearchHit hit) {
    if (queryType == QueryType.NATURAL_LANGUAGE) {
      return 1.0;
    }
    String normalizedQuery = normalizeToken(query);
    double factor = 1.0;

    String fileName = hit.fileName() == null ? "" : normalizeToken(hit.fileName());
    if (!fileName.isBlank() && (normalizedQuery.equals(fileName) || fileName.contains(normalizedQuery))) {
      factor *= properties.filenameExactBoost();
    }

    String className = hit.className() == null ? "" : normalizeToken(hit.className());
    String methodName = hit.methodName() == null ? "" : normalizeToken(hit.methodName());
    if (!className.isBlank() && normalizedQuery.equals(className)) {
      factor *= properties.symbolExactBoost();
    }
    if (!methodName.isBlank() && normalizedQuery.equals(methodName)) {
      factor *= properties.symbolExactBoost();
    }
    return factor;
  }

  private static boolean isTestPath(String path) {
    return path.contains("/src/test/")
        || path.contains("/test/")
        || path.contains("/__tests__/")
        || path.contains("/spec/")
        || path.contains("/tests/");
  }

  private static boolean isGeneratedOrBuildPath(String path) {
    return path.contains("/generated/")
        || path.contains("/build/")
        || path.contains("/target/")
        || path.contains("/node_modules/");
  }

  private static boolean isConfigPath(String path) {
    return path.endsWith(".yml")
        || path.endsWith(".yaml")
        || path.endsWith(".properties")
        || path.endsWith(".xml")
        || path.contains("/resources/application");
  }

  private static String normalizeToken(String value) {
    return value.trim().toLowerCase(Locale.ROOT).replace(".java", "");
  }
}
