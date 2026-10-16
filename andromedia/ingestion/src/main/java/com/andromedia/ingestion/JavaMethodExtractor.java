package com.andromedia.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaMethodExtractor {

  private static final Pattern CLASS_NAME_PATTERN =
      Pattern.compile("\\b(?:class|interface|enum|record)\\s+(\\w+)");

  private static final Pattern METHOD_NAME_PATTERN =
      Pattern.compile("\\s+(\\w+)\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\s*\\{?\\s*$");

  private static final Set<String> NON_METHOD_PREFIXES =
      Set.of(
          "if ", "if(", "for ", "for(", "while ", "while(", "do ", "do(",
          "switch ", "switch(", "catch ", "catch(", "return ", "throw ", "new ",
          "import ", "package ", "class ", "interface ", "enum ", "record ",
          "else", "try", "finally", "synchronized", "@");

  private JavaMethodExtractor() {}

  public static Optional<String> findClassName(String content) {
    Matcher matcher = CLASS_NAME_PATTERN.matcher(content);
    if (matcher.find()) {
      return Optional.of(matcher.group(1));
    }
    return Optional.empty();
  }

  public static List<MethodSpan> extractMethods(String content) {
    String[] lines = content.split("\n", -1);
    List<MethodSpan> methods = new ArrayList<>();
    int index = 0;
    while (index < lines.length) {
      if (!couldStartMethod(lines[index])) {
        index++;
        continue;
      }

      int signatureStart = index;
      StringBuilder signature = new StringBuilder();
      while (index < lines.length && !containsOpeningBrace(lines[index])) {
        signature.append(lines[index]).append('\n');
        index++;
        if (index - signatureStart > 30) {
          break;
        }
      }

      if (index >= lines.length || !containsOpeningBrace(lines[index])) {
        index = signatureStart + 1;
        continue;
      }

      signature.append(lines[index]);
      String signatureText = signature.toString();
      if (signatureText.contains(";") && !signatureText.contains("{")) {
        index++;
        continue;
      }

      Optional<String> methodName = extractMethodName(signatureText);
      if (methodName.isEmpty()) {
        index++;
        continue;
      }

      int startLine = signatureStart + 1;
      int braceDepth = braceDepthOnLine(lines[index]);
      int endIndex = index;
      index++;

      while (index < lines.length && braceDepth > 0) {
        braceDepth += braceDelta(lines[index]);
        endIndex = index;
        index++;
      }

      int endLine = endIndex + 1;
      String body = joinLines(lines, signatureStart, endIndex);
      methods.add(new MethodSpan(methodName.get(), startLine, endLine, body));
    }
    return methods;
  }

  private static boolean couldStartMethod(String line) {
    String trimmed = line.trim();
    if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
      return false;
    }
    String lower = trimmed.toLowerCase();
    for (String prefix : NON_METHOD_PREFIXES) {
      if (lower.startsWith(prefix)) {
        return false;
      }
    }
    return trimmed.contains("(");
  }

  private static boolean containsOpeningBrace(String line) {
    return line.contains("{");
  }

  private static Optional<String> extractMethodName(String signatureText) {
    String normalized = signatureText.replace('\n', ' ').trim();
    Matcher matcher = METHOD_NAME_PATTERN.matcher(normalized);
    if (matcher.find()) {
      return Optional.of(matcher.group(1));
    }
    return Optional.empty();
  }

  private static int braceDepthOnLine(String line) {
    int depth = 0;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (ch == '{') {
        depth++;
      } else if (ch == '}') {
        depth--;
      }
    }
    return depth;
  }

  private static int braceDelta(String line) {
    int delta = 0;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (ch == '{') {
        delta++;
      } else if (ch == '}') {
        delta--;
      }
    }
    return delta;
  }

  private static String joinLines(String[] lines, int startInclusive, int endInclusive) {
    StringBuilder builder = new StringBuilder();
    for (int i = startInclusive; i <= endInclusive; i++) {
      builder.append(lines[i]);
      if (i < endInclusive) {
        builder.append('\n');
      }
    }
    return builder.toString();
  }

  public record MethodSpan(String methodName, int startLine, int endLine, String content) {}
}
