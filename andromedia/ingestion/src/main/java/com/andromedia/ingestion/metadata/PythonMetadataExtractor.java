package com.andromedia.ingestion.metadata;

import com.andromedia.common.CodeSymbol;
import com.andromedia.common.FileStructuralMetadata;
import com.andromedia.common.StructuralMetadata;
import com.andromedia.common.SymbolIdGenerator;
import com.andromedia.common.SymbolType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PythonMetadataExtractor implements LanguageMetadataExtractor {

  private static final Pattern IMPORT_PATTERN =
      Pattern.compile("^\\s*import\\s+([\\w.]+(?:\\s+as\\s+\\w+)?)", Pattern.MULTILINE);

  private static final Pattern FROM_IMPORT_PATTERN =
      Pattern.compile("^\\s*from\\s+([\\w.]+)\\s+import\\s+(.+)", Pattern.MULTILINE);

  private static final Pattern CLASS_PATTERN =
      Pattern.compile("^\\s*class\\s+(\\w+)\\s*(?:\\([^)]*\\))?:", Pattern.MULTILINE);

  private static final Pattern FUNCTION_PATTERN =
      Pattern.compile("^\\s*def\\s+(\\w+)\\s*\\(", Pattern.MULTILINE);

  private static final Pattern ANNOTATION_PATTERN = Pattern.compile("@(\\w+(?:\\.\\w+)*)");

  @Override
  public FileStructuralMetadata extractFile(Path path, String content, String language) {
    String absolutePath = path.toAbsolutePath().normalize().toString();
    String moduleName = moduleNameFromPath(path);
    List<String> imports = extractImports(content);
    List<String> annotations = extractAnnotations(content);
    List<CodeSymbol> symbols = extractSymbols(content, absolutePath, language, moduleName);
    return new FileStructuralMetadata(imports, annotations, symbols);
  }

  @Override
  public StructuralMetadata forChunkSpan(
      FileStructuralMetadata file, int startLine, int endLine) {
    return StructuralMetadata.forChunk(file, startLine, endLine);
  }

  public static String moduleNameFromPath(Path path) {
    String fileName = path.getFileName().toString();
    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex > 0) {
      return fileName.substring(0, dotIndex);
    }
    return fileName;
  }

  static List<String> extractImports(String content) {
    List<String> imports = new ArrayList<>();
    Matcher importMatcher = IMPORT_PATTERN.matcher(content);
    while (importMatcher.find()) {
      imports.add("import " + importMatcher.group(1).trim());
    }
    Matcher fromMatcher = FROM_IMPORT_PATTERN.matcher(content);
    while (fromMatcher.find()) {
      imports.add("from " + fromMatcher.group(1) + " import " + fromMatcher.group(2).trim());
    }
    return imports;
  }

  static List<String> extractAnnotations(String content) {
    Set<String> annotations = new LinkedHashSet<>();
    Matcher matcher = ANNOTATION_PATTERN.matcher(content);
    while (matcher.find()) {
      String raw = matcher.group(1);
      int dotIndex = raw.lastIndexOf('.');
      annotations.add(dotIndex >= 0 ? raw.substring(dotIndex + 1) : raw);
    }
    return List.copyOf(annotations);
  }

  private static List<CodeSymbol> extractSymbols(
      String content, String absolutePath, String language, String moduleName) {
    List<CodeSymbol> symbols = new ArrayList<>();
    symbols.add(
        new CodeSymbol(
            SymbolIdGenerator.pythonModule(moduleName),
            moduleName,
            SymbolType.MODULE,
            absolutePath,
            1,
            countLines(content),
            language,
            null));

    Matcher classMatcher = CLASS_PATTERN.matcher(content);
    while (classMatcher.find()) {
      String className = classMatcher.group(1);
      int lineStart = lineNumber(content, classMatcher.start());
      int lineEnd = findIndentedBlockEnd(content, lineStart);
      symbols.add(
          new CodeSymbol(
              SymbolIdGenerator.pythonClass(moduleName, className),
              className,
              SymbolType.CLASS,
              absolutePath,
              lineStart,
              lineEnd,
              language,
              moduleName));
    }

    String currentClass = null;
    String[] lines = content.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      int indent = leadingWhitespace(line);
      Matcher inlineClassMatcher = CLASS_PATTERN.matcher(line);
      if (inlineClassMatcher.find() && indent == 0) {
        currentClass = inlineClassMatcher.group(1);
        continue;
      }
      Matcher functionMatcher = FUNCTION_PATTERN.matcher(line);
      if (!functionMatcher.find()) {
        continue;
      }
      String functionName = functionMatcher.group(1);
      int lineStart = i + 1;
      int lineEnd = findIndentedBlockEndFromLine(lines, i);
      if (indent > 0 && currentClass != null) {
        symbols.add(
            new CodeSymbol(
                SymbolIdGenerator.pythonClassMethod(moduleName, currentClass, functionName),
                functionName,
                SymbolType.METHOD,
                absolutePath,
                lineStart,
                lineEnd,
                language,
                currentClass));
      } else {
        symbols.add(
            new CodeSymbol(
                SymbolIdGenerator.pythonFunction(moduleName, functionName),
                functionName,
                SymbolType.FUNCTION,
                absolutePath,
                lineStart,
                lineEnd,
                language,
                moduleName));
      }
    }
    return symbols;
  }

  private static int findIndentedBlockEnd(String content, int startLine) {
    String[] lines = content.split("\n", -1);
    return findIndentedBlockEndFromLine(lines, startLine - 1);
  }

  private static int findIndentedBlockEndFromLine(String[] lines, int startIndex) {
    if (startIndex >= lines.length) {
      return startIndex + 1;
    }
    int baseIndent = leadingWhitespace(lines[startIndex]);
    int endLine = startIndex + 1;
    for (int i = startIndex + 1; i < lines.length; i++) {
      String line = lines[i];
      if (line.isBlank()) {
        endLine = i + 1;
        continue;
      }
      if (leadingWhitespace(line) <= baseIndent) {
        break;
      }
      endLine = i + 1;
    }
    return endLine;
  }

  private static int leadingWhitespace(String line) {
    int count = 0;
    while (count < line.length() && Character.isWhitespace(line.charAt(count))) {
      count++;
    }
    return count;
  }

  private static int lineNumber(String content, int charIndex) {
    int line = 1;
    for (int i = 0; i < charIndex && i < content.length(); i++) {
      if (content.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  private static int countLines(String content) {
    if (content.isEmpty()) {
      return 1;
    }
    return (int) content.lines().count();
  }
}
