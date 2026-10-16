package com.andromedia.cli.shell;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.andromedia.cli.shell.agent.ChainedIntentResolver;
import com.andromedia.cli.shell.agent.CommandDispatcher;
import com.andromedia.cli.shell.agent.ShellIntent;
import com.andromedia.cli.shell.agent.SystemCommandHandler;
import com.andromedia.cli.context.ShellSessionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

@ExtendWith(MockitoExtension.class)
class AgentShellTest {

  @Mock private CommandLineFactory commandLineFactory;
  @Mock private ChainedIntentResolver intentResolver;
  @Mock private CommandDispatcher commandDispatcher;
  @Mock private SystemCommandHandler systemCommandHandler;
  @Mock private ShellSessionContext shellSessionContext;
  @Mock private CommandLine commandLine;

  @InjectMocks private AgentShell agentShell;

  @Test
  void routesNaturalLanguageThroughDispatcher() {
    when(shellSessionContext.parseInput("explain flow"))
        .thenReturn(new ShellSessionContext.ParsedShellInput("explain flow", java.util.List.of()));
    when(intentResolver.resolve("explain flow")).thenReturn(ShellIntent.of(com.andromedia.cli.shell.agent.IntentType.ASK, "explain flow"));

    agentShell.handleLine("explain flow", commandLine);

    verify(commandDispatcher).dispatch(any(ShellIntent.class), any(String.class), any(CommandLine.class));
  }

  @Test
  void delegatesSlashCommandsToSystemHandler() {
    when(systemCommandHandler.handle("/help", commandLine)).thenReturn(false);

    agentShell.handleLine("/help", commandLine);

    verify(systemCommandHandler).handle("/help", commandLine);
  }

  @Test
  void executesExplicitPicocliLines() {
    agentShell.handleLine("search jwt", commandLine);

    verify(commandLine).execute(any(String[].class));
  }

  @Test
  void forwardsIndexFlagsThroughPicocli() {
    when(commandLine.execute(any(String[].class))).thenReturn(0);

    agentShell.handleLine("index --recreate .", commandLine);

    verify(commandLine).execute(new String[] {"index", "--recreate", "."});
  }

  @Test
  void forwardsSlashIndexFlagsThroughPicocli() {
    when(commandLine.execute(any(String[].class))).thenReturn(0);

    agentShell.handleLine("/index --recreate --embed .", commandLine);

    verify(commandLine)
        .execute(new String[] {"index", "--recreate", "--embed", "."});
  }
}
