package com.andromedia.cli.progress;

public enum CliPhase {
  HYBRID_RETRIEVAL("Searching codebase"),
  EXPANDING_CONTEXT("Analysing context"),
  PREPARING_PROMPT("Preparing answer"),
  WAITING_FOR_MODEL("Thinking"),
  RETRYING_RATE_LIMIT("Retrying after rate limit"),
  STREAMING_RESPONSE("Streaming response"),
  BM25_SEARCH("Searching index"),
  SEMANTIC_SEARCH("Searching semantically"),
  HYBRID_SEARCH("Searching codebase"),
  INDEXING_WORKSPACE("Indexing workspace"),
  EMBEDDING_CHUNKS("Generating embeddings"),
  LOADING_CONTEXT("Loading project context"),
  EXPLORING_CODEBASE("Exploring codebase"),
  ANALYSING_IMPACT("Analysing impact"),
  ANALYSING_GRAPH("Analysing dependencies"),
  GROUNDING_CONTEXT("Grounding repository context"),
  WRITING_PLAN("Writing plan"),
  LOOKING_UP_USAGES("Looking up usages"),
  SUMMARIZING("Summarizing results");

  private final String label;

  CliPhase(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public String completedLabel() {
    return switch (this) {
      case HYBRID_RETRIEVAL, BM25_SEARCH, SEMANTIC_SEARCH, HYBRID_SEARCH -> "Search completed";
      case EXPANDING_CONTEXT -> "Context ready";
      case PREPARING_PROMPT -> "Prompt ready";
      case WAITING_FOR_MODEL -> "Ready";
      case RETRYING_RATE_LIMIT -> "Retry scheduled";
      case STREAMING_RESPONSE -> "Response streamed";
      case INDEXING_WORKSPACE -> "Indexing completed";
      case EMBEDDING_CHUNKS -> "Embeddings ready";
      case LOADING_CONTEXT -> "Project context loaded";
      case EXPLORING_CODEBASE -> "Codebase explored";
      case ANALYSING_IMPACT -> "Impact analysed";
      case ANALYSING_GRAPH -> "Dependencies mapped";
      case GROUNDING_CONTEXT -> "Repository grounded";
      case WRITING_PLAN -> "Plan written";
      case LOOKING_UP_USAGES -> "Usages found";
      case SUMMARIZING -> "Summary ready";
    };
  }

  /** Footer metric name for ask pipeline summaries. */
  public String footerMetricLabel() {
    return switch (this) {
      case HYBRID_RETRIEVAL, BM25_SEARCH, SEMANTIC_SEARCH, HYBRID_SEARCH -> "Search";
      case EXPANDING_CONTEXT -> "Context";
      case WAITING_FOR_MODEL -> "Generation";
      case EXPLORING_CODEBASE -> "Explore";
      case ANALYSING_IMPACT -> "Impact";
      case ANALYSING_GRAPH -> "Graph";
      case GROUNDING_CONTEXT -> "Grounding";
      case WRITING_PLAN -> "Plan";
      default -> timingLabel();
    };
  }

  public boolean showsDurationOnCompletion() {
    return switch (this) {
      case WAITING_FOR_MODEL -> false;
      default -> true;
    };
  }

  public String timingLabel() {
    return switch (this) {
      case HYBRID_RETRIEVAL, BM25_SEARCH, SEMANTIC_SEARCH, HYBRID_SEARCH -> "Search";
      case EXPANDING_CONTEXT -> "Context ready";
      case PREPARING_PROMPT -> "Prompt ready";
      case WAITING_FOR_MODEL, RETRYING_RATE_LIMIT, STREAMING_RESPONSE -> "Generation";
      case INDEXING_WORKSPACE -> "Indexing completed";
      case EMBEDDING_CHUNKS -> "Embeddings ready";
      case LOADING_CONTEXT -> "Project context";
      case EXPLORING_CODEBASE -> "Explore";
      case ANALYSING_IMPACT -> "Impact analysis";
      case ANALYSING_GRAPH -> "Dependencies";
      case GROUNDING_CONTEXT -> "Grounding";
      case WRITING_PLAN -> "Plan generation";
      case LOOKING_UP_USAGES -> "Usages lookup";
      case SUMMARIZING -> "Summary";
    };
  }
}
