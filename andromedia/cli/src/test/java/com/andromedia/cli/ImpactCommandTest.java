package com.andromedia.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.andromedia.common.SymbolType;
import com.andromedia.search.ImpactAnalysisService;
import com.andromedia.search.ImpactCategory;
import com.andromedia.search.ImpactExplanationService;
import com.andromedia.search.ImpactLookupResult;
import com.andromedia.search.ImpactSite;
import com.andromedia.search.TraversalDirection;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

@ExtendWith(MockitoExtension.class)
class ImpactCommandTest {

  @Mock private ImpactAnalysisService impactAnalysisService;
  @Mock private ImpactExplanationService impactExplanationService;
  @Mock private CliExecutionContext executionContext;

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
  void printsFlatAffectedSymbolsByDefault() {
    when(executionContext.isDebugEnabled()).thenReturn(false);
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
                Duration.ofMillis(5)));

    CommandLine cmd =
        new CommandLine(new ImpactCommand(impactAnalysisService, impactExplanationService, executionContext));
    cmd.execute("AnomalyEvent");

    String output = out.toString();
    assertTrue(output.contains("Impact for java:com.foo.AnomalyEvent (reverse"));
    assertTrue(output.contains("Affected symbols (1):"));
    assertTrue(output.contains("AnomalyKafkaListener#consumeAnomaly"));
    assertFalse(output.contains("APIs ("));
    verify(impactAnalysisService).analyze(any());
  }

  @Test
  void printsDependenciesForForwardTraversal() {
    when(executionContext.isDebugEnabled()).thenReturn(false);
    when(impactAnalysisService.analyze(any()))
        .thenReturn(
            new ImpactLookupResult(
                List.of("java:com.foo.Service#save"),
                TraversalDirection.FORWARD,
                List.of(
                    new ImpactSite(
                        "java:com.foo.UserRepository#findById",
                        "findById",
                        SymbolType.METHOD,
                        "src/UserRepository.java",
                        10,
                        1,
                        ImpactCategory.REPOSITORY)),
                Map.of(),
                Duration.ofMillis(5)));

    CommandLine cmd =
        new CommandLine(new ImpactCommand(impactAnalysisService, impactExplanationService, executionContext));
    cmd.execute("--forward", "Service#save");

    String output = out.toString();
    assertTrue(output.contains("(forward"));
    assertTrue(output.contains("Dependencies (1):"));
    assertTrue(output.contains("UserRepository#findById"));
  }

  @Test
  void printsGroupedSectionsWhenRequested() {
    when(executionContext.isDebugEnabled()).thenReturn(false);
    ImpactSite apiSite =
        new ImpactSite(
            "java:com.foo.UserController#create",
            "create",
            SymbolType.METHOD,
            "src/UserController.java",
            42,
            2,
            ImpactCategory.API);
    Map<ImpactCategory, List<ImpactSite>> grouped = new EnumMap<>(ImpactCategory.class);
    grouped.put(ImpactCategory.API, List.of(apiSite));
    when(impactAnalysisService.analyze(any()))
        .thenReturn(
            new ImpactLookupResult(
                List.of("java:com.foo.UserService#saveUser"),
                TraversalDirection.REVERSE,
                List.of(apiSite),
                grouped,
                Duration.ofMillis(5)));

    CommandLine cmd =
        new CommandLine(new ImpactCommand(impactAnalysisService, impactExplanationService, executionContext));
    cmd.execute("--grouped", "UserService#saveUser");

    String output = out.toString();
    assertTrue(output.contains("Affected symbols (1):"));
    assertTrue(output.contains("APIs (1)"));
    assertTrue(output.contains("UserController#create"));
  }
}
