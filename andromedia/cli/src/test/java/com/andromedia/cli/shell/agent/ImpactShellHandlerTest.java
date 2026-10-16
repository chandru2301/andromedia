package com.andromedia.cli.shell.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.andromedia.cli.CliExecutionContext;
import com.andromedia.common.SymbolType;
import com.andromedia.search.ImpactAnalysisService;
import com.andromedia.search.ImpactCategory;
import com.andromedia.llm.SideQueryService;
import com.andromedia.search.ImpactExplanationService;
import com.andromedia.search.ImpactLookupResult;
import com.andromedia.search.ImpactSite;
import com.andromedia.search.TraversalDirection;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImpactShellHandlerTest {

  @Mock private ImpactAnalysisService impactAnalysisService;
  @Mock private ImpactExplanationService impactExplanationService;
  @Mock private SideQueryService sideQueryService;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private PrintStream originalOut;

  @BeforeEach
  void setUp() {
    originalOut = System.out;
    System.setOut(new PrintStream(out));
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
  }

  @Test
  void printsAffectedSymbolsForKafkaPrompt() {
    when(impactAnalysisService.analyze(any()))
        .thenReturn(
            new ImpactLookupResult(
                List.of("java:com.foo.AnomalyEvent"),
                TraversalDirection.REVERSE,
                List.of(
                    new ImpactSite(
                        "java:com.foo.AnomalyKafkaListener#consumeAnomaly",
                        "consumeAnomaly",
                        SymbolType.METHOD,
                        "src/AnomalyKafkaListener.java",
                        42,
                        1,
                        ImpactCategory.KAFKA_MESSAGING)),
                Map.of(),
                Duration.ofMillis(3)));

    ImpactShellHandler handler =
        new ImpactShellHandler(
            impactAnalysisService,
            impactExplanationService,
            sideQueryService,
            new CliExecutionContext(),
            System.out,
            System.err);

    int exitCode =
        handler.handleNaturalLanguage(
            "what kafka consumers are affected if I change AnomalyEvent?");

    assertTrue(exitCode == 0);
    String output = out.toString();
    assertTrue(output.contains("Affected symbols"));
    assertTrue(output.contains("AnomalyKafkaListener#consumeAnomaly"));
  }
}
