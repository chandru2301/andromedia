package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class QueryTypeClassifierTest {

  private final QueryTypeClassifier classifier = new QueryTypeClassifier();

  @Test
  void classifiesFilenameQueryAsIdentifier() {
    assertEquals(QueryType.IDENTIFIER, classifier.classify("AnomalyKafkaListener.java"));
  }

  @Test
  void classifiesCamelCaseAsIdentifier() {
    assertEquals(QueryType.IDENTIFIER, classifier.classify("validateJwtToken"));
  }

  @Test
  void classifiesNaturalLanguageQuery() {
    assertEquals(QueryType.NATURAL_LANGUAGE, classifier.classify("jwt bearer validation logic"));
  }

  @Test
  void classifiesMixedQueryWhenSignalsConflict() {
    assertEquals(QueryType.MIXED, classifier.classify("JwtService token validation flow"));
  }
}
