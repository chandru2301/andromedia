package com.andromedia.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.andromedia.search.MethodHint;
import com.andromedia.search.UsageLookupRequest;
import com.andromedia.search.UsageLookupResult;
import com.andromedia.search.UsageRole;
import com.andromedia.search.UsageSite;
import com.andromedia.search.UsagesLookupService;
import com.andromedia.common.ChunkUnitType;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

@ExtendWith(MockitoExtension.class)
class UsagesCommandTest {

  @Mock private UsagesLookupService usagesLookupService;
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
  void printsDefinitionsAndReferences() {
    when(executionContext.isDebugEnabled()).thenReturn(false);
    when(usagesLookupService.lookup(any(UsageLookupRequest.class)))
        .thenReturn(
            new UsageLookupResult(
                List.of(
                    new UsageSite(
                        "java:com.foo.AnomalyEvent",
                        "AnomalyEvent",
                        "/proj/AnomalyEvent.java",
                        "AnomalyEvent.java",
                        3,
                        8,
                        ChunkUnitType.CLASS,
                        "chunk-1",
                        UsageRole.DEFINITION)),
                List.of(
                    new UsageSite(
                        "java:com.foo.AnomalyEvent",
                        "AnomalyEvent",
                        "/proj/Consumer.java",
                        "Consumer.java",
                        7,
                        11,
                        ChunkUnitType.METHOD,
                        "chunk-2",
                        UsageRole.REFERENCE)),
                List.of("java:com.foo.AnomalyEvent"),
                Duration.ofMillis(5)));

    CommandLine cmd = new CommandLine(new UsagesCommand(usagesLookupService, executionContext));
    cmd.execute("AnomalyEvent");

    String output = out.toString();
    assertTrue(output.contains("Definitions (1):"));
    assertTrue(output.contains("References (1):"));
    assertTrue(output.contains("AnomalyEvent.java"));
    assertTrue(output.contains("Consumer.java"));
  }

  @Test
  void printsMethodHintsWhenClassMethodQueryMisses() {
    when(executionContext.isDebugEnabled()).thenReturn(false);
    when(usagesLookupService.lookup(any(UsageLookupRequest.class)))
        .thenReturn(
            new UsageLookupResult(
                List.of(),
                List.of(),
                List.of(),
                Duration.ofMillis(5),
                List.of(
                    new MethodHint(
                        "consumeAnomaly",
                        "java:com.cursr.backend.anomaly.AnomalyKafkaListener#consumeAnomaly"))));

    CommandLine cmd = new CommandLine(new UsagesCommand(usagesLookupService, executionContext));
    cmd.execute("AnomalyKafkaListener#onMessage");

    String output = out.toString();
    assertTrue(output.contains("No symbols matched: AnomalyKafkaListener#onMessage"));
    assertTrue(output.contains("Methods on AnomalyKafkaListener:"));
    assertTrue(output.contains("consumeAnomaly"));
    assertTrue(output.contains("Try: andro usages 'AnomalyKafkaListener#consumeAnomaly'"));
  }
}
