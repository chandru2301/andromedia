package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.lucene.queryparser.classic.QueryParser;
import org.junit.jupiter.api.Test;

class LuceneSearchServiceQueryPrepTest {

  @Test
  void prepareQueryTextStripsHistoryThenDecisionFiller() {
    assertEquals(
        "did we to switch to kafka",
        LuceneSearchService.prepareQueryText("why did we decide to switch to Kafka?"));
  }

  @Test
  void prepareQueryTextLeavesIdentifierQueriesAlone() {
    assertEquals("UserService", LuceneSearchService.prepareQueryText("UserService"));
  }

  @Test
  void operatorForUsesOrOnDecisionAndNaturalLanguage() {
    assertEquals(
        QueryParser.Operator.OR,
        LuceneSearchService.operatorFor("why did we decide to switch to Kafka"));
    assertEquals(
        QueryParser.Operator.OR,
        LuceneSearchService.operatorFor("how does payment timeout handling work"));
  }

  @Test
  void operatorForUsesAndOnIdentifiers() {
    assertEquals(QueryParser.Operator.AND, LuceneSearchService.operatorFor("UserService"));
    assertEquals(QueryParser.Operator.AND, LuceneSearchService.operatorFor("PaymentClient"));
  }
}
