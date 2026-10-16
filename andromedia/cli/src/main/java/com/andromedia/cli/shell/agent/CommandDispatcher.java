package com.andromedia.cli.shell.agent;

import java.io.PrintStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

@Component
public class CommandDispatcher {

  private final ImpactShellHandler impactShellHandler;
  private final PlanShellHandler planShellHandler;
  private final PrintStream err;

  @Autowired
  public CommandDispatcher(
      ImpactShellHandler impactShellHandler, PlanShellHandler planShellHandler) {
    this(impactShellHandler, planShellHandler, System.err);
  }

  CommandDispatcher(
      ImpactShellHandler impactShellHandler, PlanShellHandler planShellHandler, PrintStream err) {
    this.impactShellHandler = impactShellHandler;
    this.planShellHandler = planShellHandler;
    this.err = err;
  }

  public int dispatch(ShellIntent shellIntent, String naturalLanguageLine, CommandLine commandLine) {
    if (shellIntent.intent() == IntentType.IMPACT) {
      return impactShellHandler.handleNaturalLanguage(naturalLanguageLine, shellIntent);
    }
    if (shellIntent.intent() == IntentType.PLAN) {
      return planShellHandler.handleNaturalLanguage(shellIntent.effectiveQuery(naturalLanguageLine));
    }
    String[] argv = buildArgv(shellIntent, naturalLanguageLine);
    if (argv == null) {
      return 1;
    }
    return commandLine.execute(argv);
  }

  String[] buildArgv(ShellIntent shellIntent, String naturalLanguageLine) {
    String trimmed = naturalLanguageLine == null ? "" : naturalLanguageLine.trim();
    String query = shellIntent.effectiveQuery(trimmed);
    return switch (shellIntent.intent()) {
      case ASK -> buildAskArgv(query, shellIntent);
      case SEARCH -> new String[] {"search", "--hybrid", query};
      case USAGES -> buildUsagesArgv(trimmed, shellIntent);
      case IMPACT, PLAN -> null;
      case INDEX -> buildIndexArgv(trimmed);
      case FIX -> {
        err.println(
            "Fix is not available yet. Try asking about the bug, e.g.: ask \""
                + trimmed
                + "\"");
        yield null;
      }
      case SYSTEM -> null;
    };
  }

  private String[] buildAskArgv(String query, ShellIntent shellIntent) {
    if (Boolean.TRUE.equals(shellIntent.graphContext())) {
      return new String[] {"ask", "--graph-context", query};
    }
    return new String[] {"ask", query};
  }

  private String[] buildIndexArgv(String trimmed) {
    String path = extractPath(trimmed);
    if (path == null || path.isBlank()) {
      err.println("Could not detect a path. Use: /index <path>");
      return null;
    }
    return new String[] {"index", path};
  }

  private String[] buildUsagesArgv(String trimmed, ShellIntent shellIntent) {
    String symbol =
        shellIntent.effectiveSymbol().orElseGet(() -> extractUsagesSymbol(trimmed));
    if (symbol == null || symbol.isBlank()) {
      err.println("Could not detect a symbol. Try: usages AnomalyEvent");
      return null;
    }
    return new String[] {"usages", symbol};
  }

  static String extractUsagesSymbol(String line) {
    String lower = line.toLowerCase();
    if (lower.startsWith("usages of ")) {
      return line.substring("usages of ".length()).trim();
    }
    if (lower.startsWith("who uses ")) {
      return line.substring("who uses ".length()).trim();
    }
    int usedIndex = lower.indexOf(" used");
    if (usedIndex > 0) {
      String prefix = line.substring(0, usedIndex).trim();
      if (prefix.toLowerCase().startsWith("where is ")) {
        return prefix.substring("where is ".length()).trim();
      }
      if (prefix.toLowerCase().startsWith("where are ")) {
        return prefix.substring("where are ".length()).trim();
      }
    }
    return line.trim();
  }

  static String extractPath(String line) {
    java.util.regex.Matcher matcher = PATH_TOKEN.matcher(line);
    if (matcher.find()) {
      return matcher.group(1);
    }
    return null;
  }

  private static final java.util.regex.Pattern PATH_TOKEN =
      java.util.regex.Pattern.compile("([/\\\\~.][^\\s\"']+|[A-Za-z]:\\\\[^\\s\"']+)");

  public static String[] parsePicocliLine(String line) {
    java.util.List<String> tokens = new java.util.ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (ch == '"') {
        inQuotes = !inQuotes;
      } else if (Character.isWhitespace(ch) && !inQuotes) {
        if (current.length() > 0) {
          tokens.add(current.toString());
          current.setLength(0);
        }
      } else {
        current.append(ch);
      }
    }
    if (current.length() > 0) {
      tokens.add(current.toString());
    }
    return tokens.toArray(String[]::new);
  }
}
