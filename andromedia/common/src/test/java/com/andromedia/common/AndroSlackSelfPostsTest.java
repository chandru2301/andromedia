package com.andromedia.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AndroSlackSelfPostsTest {

  @Test
  void dropsAndroAlertAndInvestigatePosts() {
    assertTrue(
        AndroSlackSelfPosts.isSelfPostMessageText(
            "*Andro pipeline alert — local* 3 new signals after Zoho reindex"));
    assertTrue(
        AndroSlackSelfPosts.isSelfPostMessageText(
            "*Andro investigate — Reliance Data Center Expansion*\nBased on the ledger"));
    assertFalse(
        AndroSlackSelfPosts.isSelfPostMessageText(
            "Bright Horizon deal is actually stalled, Fatima went quiet"));
  }

  @Test
  void dropsIndexedChunksAuthoredByCausrAlert() {
    String chunk =
        """
        [SLACK] #all-causr · 2026-08-17 · causr_alert
        thread: 1 messages · reactions: 0

        causr_alert: *Andro pipeline alert — local* 8 new signals
        """;
    assertTrue(AndroSlackSelfPosts.isIndexedSelfPost("SLACK", chunk));
    assertFalse(
        AndroSlackSelfPosts.isIndexedSelfPost(
            "SLACK",
            "[SLACK] #all-causr · 2026-08-17 · Chandru\nChandru: Bright Horizon deal is actually stalled"));
    assertFalse(AndroSlackSelfPosts.isIndexedSelfPost("ZOHO", chunk));
  }
}
