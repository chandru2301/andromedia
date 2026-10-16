package com.andromedia.core.trial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TrialIdMapperTest {

  @Test
  void mapsClerkUserIdPreservingUnderscore() {
    assertEquals("user_2abcXYZ", TrialIdMapper.fromClerkUserId("user_2abcXYZ"));
  }

  @Test
  void matchesDerivedTrialId() {
    assertTrue(TrialIdMapper.matches("user_2abc", "user_2abc"));
  }
}
