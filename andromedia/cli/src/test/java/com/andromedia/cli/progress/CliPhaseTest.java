package com.andromedia.cli.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CliPhaseTest {

  @Test
  void planPhasesUseConversationalLabels() {
    assertEquals("Loading project context", CliPhase.LOADING_CONTEXT.label());
    assertEquals("Project context loaded", CliPhase.LOADING_CONTEXT.completedLabel());
    assertEquals("Exploring codebase", CliPhase.EXPLORING_CODEBASE.label());
    assertEquals("Codebase explored", CliPhase.EXPLORING_CODEBASE.completedLabel());
    assertEquals("Analysing impact", CliPhase.ANALYSING_IMPACT.label());
    assertEquals("Impact analysed", CliPhase.ANALYSING_IMPACT.completedLabel());
    assertEquals("Analysing dependencies", CliPhase.ANALYSING_GRAPH.label());
    assertEquals("Dependencies mapped", CliPhase.ANALYSING_GRAPH.completedLabel());
    assertEquals("Writing plan", CliPhase.WRITING_PLAN.label());
    assertEquals("Plan written", CliPhase.WRITING_PLAN.completedLabel());
    assertEquals("Grounding repository context", CliPhase.GROUNDING_CONTEXT.label());
    assertEquals("Repository grounded", CliPhase.GROUNDING_CONTEXT.completedLabel());
  }

  @Test
  void refreshedAskAndSearchLabels() {
    assertEquals("Searching codebase", CliPhase.HYBRID_RETRIEVAL.label());
    assertEquals("Search completed", CliPhase.HYBRID_RETRIEVAL.completedLabel());
    assertEquals("Analysing context", CliPhase.EXPANDING_CONTEXT.label());
    assertEquals("Context ready", CliPhase.EXPANDING_CONTEXT.completedLabel());
    assertEquals("Preparing answer", CliPhase.PREPARING_PROMPT.label());
    assertEquals("Prompt ready", CliPhase.PREPARING_PROMPT.completedLabel());
    assertEquals("Thinking", CliPhase.WAITING_FOR_MODEL.label());
    assertEquals("Ready", CliPhase.WAITING_FOR_MODEL.completedLabel());
    assertEquals("Searching index", CliPhase.BM25_SEARCH.label());
    assertEquals("Searching semantically", CliPhase.SEMANTIC_SEARCH.label());
    assertEquals("Indexing workspace", CliPhase.INDEXING_WORKSPACE.label());
    assertEquals("Generating embeddings", CliPhase.EMBEDDING_CHUNKS.label());
  }

  @Test
  void impactAndUsagesPhases() {
    assertEquals("Looking up usages", CliPhase.LOOKING_UP_USAGES.label());
    assertEquals("Usages found", CliPhase.LOOKING_UP_USAGES.completedLabel());
    assertEquals("Summarizing results", CliPhase.SUMMARIZING.label());
    assertEquals("Summary ready", CliPhase.SUMMARIZING.completedLabel());
    assertEquals(false, CliPhase.WAITING_FOR_MODEL.showsDurationOnCompletion());
  }
}
