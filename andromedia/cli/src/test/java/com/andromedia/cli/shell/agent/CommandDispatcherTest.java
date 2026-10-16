package com.andromedia.cli.shell.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class CommandDispatcherTest {

  @Test
  void buildsAskAndSearchArgv() {
    CommandDispatcher dispatcher =
        new CommandDispatcher(
            mock(ImpactShellHandler.class),
            mock(PlanShellHandler.class),
            new PrintStream(new ByteArrayOutputStream()));

    assertArrayEquals(
        new String[] {"ask", "explain websocket flow"},
        dispatcher.buildArgv(ShellIntent.of(IntentType.ASK, "explain websocket flow"), "explain websocket flow"));

    assertArrayEquals(
        new String[] {"ask", "--graph-context", "what breaks if I change Foo?"},
        dispatcher.buildArgv(
            ShellIntent.ask("what breaks if I change Foo?", true),
            "what breaks if I change Foo?"));

    assertArrayEquals(
        new String[] {"search", "--hybrid", "where is auth filter implemented?"},
        dispatcher.buildArgv(
            ShellIntent.of(IntentType.SEARCH, "where is auth filter implemented?"),
            "where is auth filter implemented?"));

    assertArrayEquals(
        new String[] {"usages", "AnomalyEvent"},
        dispatcher.buildArgv(
            ShellIntent.of(IntentType.USAGES, "usages of AnomalyEvent"), "usages of AnomalyEvent"));

    assertNull(
        dispatcher.buildArgv(
            ShellIntent.of(IntentType.IMPACT, "impact UserService#saveUser"),
            "impact UserService#saveUser"));
  }

  @Test
  void dispatchesImpactToShellHandler() {
    ImpactShellHandler handler = mock(ImpactShellHandler.class);
    when(handler.handleNaturalLanguage(eq("what breaks if I change AnomalyEvent?"), any()))
        .thenReturn(0);
    CommandDispatcher dispatcher =
        new CommandDispatcher(handler, mock(PlanShellHandler.class), new PrintStream(new ByteArrayOutputStream()));

    int exitCode =
        dispatcher.dispatch(
            ShellIntent.of(IntentType.IMPACT, "what breaks if I change AnomalyEvent?"),
            "what breaks if I change AnomalyEvent?",
            mock(CommandLine.class));

    assertEquals(0, exitCode);
    verify(handler).handleNaturalLanguage(eq("what breaks if I change AnomalyEvent?"), any());
  }

  @Test
  void fixIntentReturnsNullAndMessage() {
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    CommandDispatcher dispatcher =
        new CommandDispatcher(
            mock(ImpactShellHandler.class),
            mock(PlanShellHandler.class),
            new PrintStream(err, true));

    assertNull(dispatcher.buildArgv(ShellIntent.of(IntentType.FIX, "fix the bug"), "fix the bug"));

    String text = err.toString(StandardCharsets.UTF_8);
    assertTrue(text.contains("not available"));
  }

  @Test
  void parsesQuotedPicocliLines() {
    assertArrayEquals(
        new String[] {"ask", "-n", "4", "explain flow"},
        CommandDispatcher.parsePicocliLine("ask -n 4 \"explain flow\""));
  }
}
