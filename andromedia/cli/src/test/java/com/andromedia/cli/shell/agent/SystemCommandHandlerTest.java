package com.andromedia.cli.shell.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.cli.CliExecutionContext;
import com.andromedia.cli.context.ProjectRulesLoader;
import com.andromedia.cli.context.ShellSessionContext;
import com.andromedia.llm.ChatProperties;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

@ExtendWith(MockitoExtension.class)
class SystemCommandHandlerTest {

  @Mock private CommandLine commandLine;
  @Mock private ShellSessionContext shellSessionContext;

  @Test
  void togglesDebugFlag() {
    CliExecutionContext context = new CliExecutionContext();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    SystemCommandHandler handler =
        SystemCommandHandler.forTest(
            context,
            new ChatProperties(),
            shellSessionContext,
            new ProjectRulesLoader(),
            new PrintStream(out, true),
            new PrintStream(out, true));

    assertFalse(handler.handle("/debug on", commandLine));
    assertTrue(context.isDebugEnabled());

    assertFalse(handler.handle("/debug off", commandLine));
    assertFalse(context.isDebugEnabled());
  }

  @Test
  void exitReturnsTrue() {
    CliExecutionContext context = new CliExecutionContext();
    SystemCommandHandler handler =
        SystemCommandHandler.forTest(
            context,
            new ChatProperties(),
            shellSessionContext,
            new ProjectRulesLoader(),
            System.out,
            System.err);

    assertTrue(handler.handle("/exit", commandLine));
    assertTrue(handler.handle("quit", commandLine));
  }
}
