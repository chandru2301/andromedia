package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ArchitectureIntentDetectorTest {

  @Test
  void detectsArchitecturePhrasesAndTokens() {
    assertTrue(ArchitectureIntentDetector.isArchitectureIntent("how does authentication work"));
    assertTrue(ArchitectureIntentDetector.isArchitectureIntent("why was JWT built this way"));
    assertTrue(ArchitectureIntentDetector.isArchitectureIntent("show the ADR for auth"));
    assertTrue(ArchitectureIntentDetector.isArchitectureIntent("architecture overview"));
  }

  @Test
  void ignoresUnrelatedQueries() {
    assertFalse(ArchitectureIntentDetector.isArchitectureIntent("UserService"));
    assertFalse(ArchitectureIntentDetector.isArchitectureIntent("token validation logic"));
  }
}
