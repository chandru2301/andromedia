package com.andromedia.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.andromedia.common.ChunkUnitType;
import com.andromedia.llm.AssembledContext;
import com.andromedia.llm.ContextAssembler;
import com.andromedia.search.SearchDiagnostics;
import com.andromedia.search.SearchHit;
import com.andromedia.search.SearchRequest;
import com.andromedia.search.SearchResult;
import com.andromedia.search.SearchService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import picocli.CommandLine;

class SearchCommandTest {

  @Test
  void printsCleanOutputWithoutDebugDiagnostics() {
    CliExecutionContext context = new CliExecutionContext();
    context.setDebugEnabled(false);
    SearchService searchService = Mockito.mock(SearchService.class);
    when(searchService.search(any(SearchRequest.class))).thenReturn(buildResult());

    SearchCommand command = new SearchCommand(searchService, stubContextAssembler(), context);
    CommandLine commandLine = new CommandLine(command);

    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    try {
      System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
      System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
      commandLine.execute("service", "-n", "5");
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }

    String outText = stdout.toString(StandardCharsets.UTF_8);
    String errText = stderr.toString(StandardCharsets.UTF_8);
    assertTrue(outText.contains("JwtService.java:42-58"));
    assertTrue(outText.contains("validateToken"));
    assertFalse(errText.contains("[debug]"));
  }

  @Test
  void printsDebugDiagnosticsToStderrWhenEnabled() {
    CliExecutionContext context = new CliExecutionContext();
    context.setDebugEnabled(true);
    SearchService searchService = Mockito.mock(SearchService.class);
    when(searchService.search(any(SearchRequest.class))).thenReturn(buildResult());

    SearchCommand command = new SearchCommand(searchService, stubContextAssembler(), context);
    CommandLine commandLine = new CommandLine(command);

    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    try {
      System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
      System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
      commandLine.execute("service");
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }

    String outText = stdout.toString(StandardCharsets.UTF_8);
    String errText = stderr.toString(StandardCharsets.UTF_8);
    assertTrue(outText.contains("JwtService.java:42-58"));
    assertTrue(outText.contains("validateToken"));
    assertTrue(errText.contains("[debug] query='service'"));
    assertTrue(errText.contains("[debug] workspaceRoot=/workspace"));
    assertTrue(errText.contains("[debug] indexPath=/home/user/.andromedia/index/abc12345"));
  }

  private static ContextAssembler stubContextAssembler() {
    ContextAssembler assembler = Mockito.mock(ContextAssembler.class);
    when(assembler.assemble(Mockito.anyList())).thenReturn(new AssembledContext(List.of()));
    return assembler;
  }

  private static SearchResult buildResult() {
    return new SearchResult(
        List.of(
            new SearchHit(
                "/workspace/JwtService.java",
                "JwtService.java",
                1.0f,
                "chunk-1",
                "JwtService",
                "validateToken",
                ChunkUnitType.METHOD,
                42,
                58)),
        1,
        Duration.ofMillis(12),
        new SearchDiagnostics("service", "/workspace", "/home/user/.andromedia/index/abc12345"));
  }
}
