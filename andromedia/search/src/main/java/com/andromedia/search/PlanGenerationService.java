package com.andromedia.search;

import com.andromedia.common.PlanStore;
import com.andromedia.common.RetrievalReference;
import com.andromedia.llm.AskProperties;
import com.andromedia.llm.ChatProperties;
import com.andromedia.llm.PromptAttachments;
import com.andromedia.llm.RagPrompt;
import com.andromedia.llm.StructuredCompletionService;
import com.andromedia.search.grounding.ArchitectureHint;
import com.andromedia.search.grounding.ArchitecturePatternDetector;
import com.andromedia.search.grounding.GroundingBundle;
import com.andromedia.search.grounding.GroundingValidationReport;
import com.andromedia.search.grounding.PlanGroundingContext;
import com.andromedia.search.grounding.PlanGroundingValidator;
import com.andromedia.search.grounding.RepositoryGroundingService;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class PlanGenerationService {

  private static final String SYSTEM_PROMPT =
      """
      You are a software architect producing an implementation plan from deterministic codebase analysis.
      Use ONLY the provided search hits, impact results, graph context, project rules, and verified grounding lists.
      Files and symbols listed under EXISTING FILES and KNOWN SYMBOLS are verified in the repository — cite them normally.
      Anything NOT in those lists must be labeled exactly as "Suggested new file:" or "Suggested new symbol:" — never imply it already exists.
      When REUSE EXISTING PATTERNS lists an abstraction, extend it instead of creating a parallel type.
      Do not invent files or symbols not present in the context.
      Output markdown with sections:
      ## Requirements
      ## Affected symbols
      ## Critical files
      ## Implementation steps
      ## Risks
      ## Test plan
      End with ### Critical Files for Implementation listing 3-5 paths. Mark unverified paths as "Suggested new file: ...".
      """;

  private final SearchService searchService;
  private final ImpactAnalysisService impactAnalysisService;
  private final GraphContextEnricher graphContextEnricher;
  private final ExploreService exploreService;
  private final UsagesLookupService usagesLookupService;
  private final RepositoryGroundingService repositoryGroundingService;
  private final ArchitecturePatternDetector architecturePatternDetector;
  private final PlanGroundingValidator planGroundingValidator;
  private final StructuredCompletionService completionService;
  private final AskProperties askProperties;
  private final ChatProperties chatProperties;

  public PlanGenerationService(
      SearchService searchService,
      ImpactAnalysisService impactAnalysisService,
      GraphContextEnricher graphContextEnricher,
      ExploreService exploreService,
      UsagesLookupService usagesLookupService,
      RepositoryGroundingService repositoryGroundingService,
      ArchitecturePatternDetector architecturePatternDetector,
      PlanGroundingValidator planGroundingValidator,
      @Qualifier("structuredCompletionService") StructuredCompletionService completionService,
      AskProperties askProperties,
      ChatProperties chatProperties) {
    this.searchService = searchService;
    this.impactAnalysisService = impactAnalysisService;
    this.graphContextEnricher = graphContextEnricher;
    this.exploreService = exploreService;
    this.usagesLookupService = usagesLookupService;
    this.repositoryGroundingService = repositoryGroundingService;
    this.architecturePatternDetector = architecturePatternDetector;
    this.planGroundingValidator = planGroundingValidator;
    this.completionService = completionService;
    this.askProperties = askProperties;
    this.chatProperties = chatProperties;
  }

  public PlanResult generate(
      String description,
      String symbolQuery,
      int depth,
      boolean deepExplore,
      PromptAttachments attachments) {
    return generate(
        description, symbolQuery, depth, deepExplore, attachments, true, PlanProgressCallback.NOOP);
  }

  public PlanResult generate(
      String description,
      String symbolQuery,
      int depth,
      boolean deepExplore,
      PromptAttachments attachments,
      PlanProgressCallback progress) {
    return generate(description, symbolQuery, depth, deepExplore, attachments, true, progress);
  }

  public PlanResult generate(
      String description,
      String symbolQuery,
      int depth,
      boolean deepExplore,
      PromptAttachments attachments,
      boolean grounded,
      PlanProgressCallback progress) {
    Instant exploreStartedAt = Instant.now();
    progress.onPhase(
        PlanProgressPhase.EXPLORING_CODEBASE,
        deepExplore ? "deep explore" : null,
        PlanProgressCallback.PhaseEvent.START,
        null);
    ExploreService.ExploreResult explore =
        deepExplore
            ? exploreService.explore(description, symbolQuery)
            : lightweightExplore(description, symbolQuery);
    String exploreDetail =
        deepExplore
            ? "deep explore"
            : explore.searchResult().hits().size() + " chunks";
    progress.onPhase(
        PlanProgressPhase.EXPLORING_CODEBASE,
        exploreDetail,
        PlanProgressCallback.PhaseEvent.COMPLETE,
        Duration.between(exploreStartedAt, Instant.now()));

    Optional<ImpactLookupResult> reverseImpact = Optional.empty();
    Optional<ImpactLookupResult> forwardImpact = Optional.empty();
    String impactSection = "";
    if (symbolQuery != null && !symbolQuery.isBlank()) {
      Instant impactStartedAt = Instant.now();
      progress.onPhase(
          PlanProgressPhase.ANALYSING_IMPACT,
          symbolQuery,
          PlanProgressCallback.PhaseEvent.START,
          null);
      ImpactAnalysis impactAnalysis = analyzeImpact(symbolQuery, depth);
      reverseImpact = Optional.of(impactAnalysis.reverse());
      forwardImpact = Optional.of(impactAnalysis.forward());
      impactSection = impactAnalysis.formattedSection();
      progress.onPhase(
          PlanProgressPhase.ANALYSING_IMPACT,
          symbolQuery,
          PlanProgressCallback.PhaseEvent.COMPLETE,
          Duration.between(impactStartedAt, Instant.now()));
    }

    Instant graphStartedAt = Instant.now();
    progress.onPhase(
        PlanProgressPhase.ANALYSING_GRAPH, null, PlanProgressCallback.PhaseEvent.START, null);
    Optional<GraphContextEnricher.GraphContextBlock> graphBlock =
        graphContextEnricher.enrich(
            description + " " + (symbolQuery == null ? "" : symbolQuery), depth, 15);
    String graphDetail =
        graphBlock
            .map(block -> block.seedSymbolIds().size() + " seeds")
            .orElse(null);
    progress.onPhase(
        PlanProgressPhase.ANALYSING_GRAPH,
        graphDetail,
        PlanProgressCallback.PhaseEvent.COMPLETE,
        Duration.between(graphStartedAt, Instant.now()));
    String graphSection = graphBlock.map(GraphContextEnricher.GraphContextBlock::text).orElse("");

    Optional<UsageLookupResult> usagesResult = Optional.empty();
    if (deepExplore && symbolQuery != null && !symbolQuery.isBlank()) {
      usagesResult =
          Optional.of(
              usagesLookupService.lookup(new UsageLookupRequest(symbolQuery, 10, true, true)));
    }

    GroundingBundle grounding = GroundingBundle.empty();
    List<ArchitectureHint> architectureHints = List.of();
    String groundingPromptSection = "";
    String architecturePromptSection = "";
    if (grounded) {
      Instant groundingStartedAt = Instant.now();
      progress.onPhase(
          PlanProgressPhase.GROUNDING_CONTEXT, null, PlanProgressCallback.PhaseEvent.START, null);
      PlanGroundingContext groundingContext =
          new PlanGroundingContext(
              description,
              symbolQuery,
              explore.searchResult(),
              reverseImpact,
              forwardImpact,
              graphBlock,
              usagesResult);
      grounding = repositoryGroundingService.build(groundingContext);
      architectureHints =
          architecturePatternDetector.detect(description, grounding, reverseImpact);
      groundingPromptSection = repositoryGroundingService.formatForPrompt(grounding);
      architecturePromptSection = ArchitecturePatternDetector.formatForPrompt(architectureHints);
      String groundingDetail = grounding.fileCount() + " files, " + grounding.symbolCount() + " symbols";
      progress.onPhase(
          PlanProgressPhase.GROUNDING_CONTEXT,
          groundingDetail,
          PlanProgressCallback.PhaseEvent.COMPLETE,
          Duration.between(groundingStartedAt, Instant.now()));
    }

    String userMessage =
        buildUserMessage(
            description,
            groundingPromptSection,
            architecturePromptSection,
            explore.summaryText(),
            impactSection,
            graphSection,
            attachments);

    Instant writingStartedAt = Instant.now();
    progress.onPhase(
        PlanProgressPhase.WRITING_PLAN,
        chatProperties.model(),
        PlanProgressCallback.PhaseEvent.START,
        null);
    String rawMarkdown =
        completionService.complete(new RagPrompt(SYSTEM_PROMPT.trim(), userMessage.trim()));
    progress.onPhase(
        PlanProgressPhase.WRITING_PLAN,
        chatProperties.model(),
        PlanProgressCallback.PhaseEvent.COMPLETE,
        Duration.between(writingStartedAt, Instant.now()));

    GroundingValidationReport validation = GroundingValidationReport.empty();
    String markdown = rawMarkdown;
    if (grounded) {
      validation =
          planGroundingValidator.validate(
              rawMarkdown, grounding, architectureHints, RepositoryGroundingService.resolveWorkspaceRoot());
      markdown = planGroundingValidator.appendValidationSection(rawMarkdown, validation, architectureHints);
    }

    String slug = PlanStore.generateSlug();
    Path savedPath;
    try {
      savedPath = PlanStore.writePlan(slug, markdown);
    } catch (IOException ex) {
      throw new SearchException("Failed to write plan file", ex);
    }

    return new PlanResult(
        markdown, savedPath, explore.searchResult(), slug, grounding, validation);
  }

  private ExploreService.ExploreResult lightweightExplore(String description, String symbolQuery) {
    SearchResult searchResult =
        searchService.search(new SearchRequest(description, 8, SearchMode.HYBRID));
    List<RetrievalReference> references =
        searchResult.hits().stream().map(SearchHit::toRetrievalReference).toList();
    StringBuilder summary = new StringBuilder();
    for (SearchHit hit : searchResult.hits()) {
      summary
          .append("- ")
          .append(hit.fileName())
          .append(" ")
          .append(hit.className() != null ? hit.className() : "")
          .append('\n');
    }
    if (symbolQuery != null && !symbolQuery.isBlank()) {
      summary.append("\nTarget symbol: ").append(symbolQuery).append('\n');
    }
    return new ExploreService.ExploreResult(summary.toString().trim(), references, searchResult);
  }

  private ImpactAnalysis analyzeImpact(String symbolQuery, int depth) {
    try {
      ImpactLookupResult reverse =
          impactAnalysisService.analyze(
              new ImpactLookupRequest(
                  symbolQuery, TraversalDirection.REVERSE, depth, 20, false, Set.of()));
      ImpactLookupResult forward =
          impactAnalysisService.analyze(
              new ImpactLookupRequest(
                  symbolQuery, TraversalDirection.FORWARD, depth, 20, false, Set.of()));
      String formatted =
          """
          Reverse impact (blast radius):
          %s

          Forward impact (dependencies):
          %s
          """
              .formatted(
                  ImpactExplanationService.formatResults(reverse),
                  ImpactExplanationService.formatResults(forward));
      return new ImpactAnalysis(reverse, forward, formatted);
    } catch (RuntimeException ex) {
      return new ImpactAnalysis(
          new ImpactLookupResult(
              List.of(), TraversalDirection.REVERSE, List.of(), java.util.Map.of(), Duration.ZERO),
          new ImpactLookupResult(
              List.of(), TraversalDirection.FORWARD, List.of(), java.util.Map.of(), Duration.ZERO),
          "Impact analysis unavailable: " + ex.getMessage());
    }
  }

  private String buildUserMessage(
      String description,
      String groundingSection,
      String architectureSection,
      String exploreSummary,
      String impactSection,
      String graphSection,
      PromptAttachments attachments) {
    StringBuilder builder = new StringBuilder();
    builder.append("Plan request:\n").append(description).append("\n\n");
    if (attachments != null && attachments.hasAny()) {
      appendSection(builder, "Project rules", attachments.projectRules());
      appendSection(builder, "Project memory", attachments.projectMemory());
      appendSection(builder, "File attachments", attachments.fileAttachments());
    }
    appendRawSection(builder, groundingSection);
    appendRawSection(builder, architectureSection);
    appendSection(builder, "Explore summary", exploreSummary);
    appendSection(builder, "Impact analysis", impactSection);
    appendSection(builder, "Graph context", graphSection);
    return builder.toString().trim();
  }

  private static void appendSection(StringBuilder builder, String title, String content) {
    if (content == null || content.isBlank()) {
      return;
    }
    builder.append("--- ").append(title).append(" ---\n").append(content).append("\n\n");
  }

  private static void appendRawSection(StringBuilder builder, String content) {
    if (content == null || content.isBlank()) {
      return;
    }
    builder.append(content).append("\n\n");
  }

  private record ImpactAnalysis(
      ImpactLookupResult reverse, ImpactLookupResult forward, String formattedSection) {}

  public record PlanResult(
      String markdown,
      Path savedPath,
      SearchResult searchResult,
      String slug,
      GroundingBundle grounding,
      GroundingValidationReport validation) {

    public PlanResult(String markdown, Path savedPath, SearchResult searchResult, String slug) {
      this(markdown, savedPath, searchResult, slug, GroundingBundle.empty(), GroundingValidationReport.empty());
    }
  }
}
