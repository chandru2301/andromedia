package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SymbolQueryParserTest {

  @Test
  void parsesSimpleClassMethodQuery() {
    var parsed = SymbolQueryParser.parseClassMethodQuery("AnomalyKafkaListener#consumeAnomaly");
    assertTrue(parsed.isPresent());
    assertEquals("AnomalyKafkaListener", parsed.get().className());
    assertEquals("consumeAnomaly", parsed.get().methodName());
  }

  @Test
  void parsesFullJavaSymbolIdMethodQuery() {
    var parsed =
        SymbolQueryParser.parseClassMethodQuery(
            "java:com.cursr.backend.anomaly.AnomalyKafkaListener#consumeAnomaly");
    assertTrue(parsed.isPresent());
    assertEquals("AnomalyKafkaListener", parsed.get().className());
    assertEquals("consumeAnomaly", parsed.get().methodName());
  }

  @Test
  void doesNotParseSimpleNameWithoutHash() {
    assertTrue(SymbolQueryParser.parseClassMethodQuery("AnomalyEvent").isEmpty());
  }
}
