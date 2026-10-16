package com.andromedia.search;

final class SearchTestSupport {

  private SearchTestSupport() {}

  static HeuristicRanker heuristicRanker() {
    return new HeuristicRanker(new RankingHeuristicsProperties(), new QueryTypeClassifier());
  }

  static QueryTypeClassifier queryTypeClassifier() {
    return new QueryTypeClassifier();
  }

  static RankingHeuristicsProperties rankingProperties() {
    return new RankingHeuristicsProperties();
  }
}
