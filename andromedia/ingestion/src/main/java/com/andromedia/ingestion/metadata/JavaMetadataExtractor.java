package com.andromedia.ingestion.metadata;

import com.andromedia.common.CodeSymbol;
import com.andromedia.common.FileStructuralMetadata;
import com.andromedia.common.StructuralMetadata;
import com.andromedia.common.SymbolIdGenerator;
import com.andromedia.common.SymbolType;
import com.andromedia.ingestion.JavaMethodExtractor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaMetadataExtractor implements LanguageMetadataExtractor {

  private static final Pattern PACKAGE_PATTERN =
      Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

  private static final Pattern IMPORT_PATTERN =
      Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.*]+)\\s*;", Pattern.MULTILINE);

  private static final Pattern TYPE_DECLARATION_PATTERN =
      Pattern.compile(
          "^\\s*(?:public\\s+|protected\\s+|private\\s+|abstract\\s+|static\\s+|final\\s+)*"
              + "(class|interface|enum|record)\\s+(\\w+)\\b",
          Pattern.MULTILINE);

  private static final Pattern ANNOTATION_PATTERN = Pattern.compile("@(\\w+(?:\\.\\w+)*)");

  private static final Pattern FIELD_PATTERN =
      Pattern.compile(
          "^\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*"
              + "(?:public|protected|private|static|final|volatile|transient|\\s)+"
              + "([\\w.<>\\[\\], ?]+?)\\s+(\\w+)\\s*(?:=|;)",
          Pattern.MULTILINE);

  @Override
  public FileStructuralMetadata extractFile(Path path, String content, String language) {
    String absolutePath = path.toAbsolutePath().normalize().toString();
    String packageName = extractPackage(content).orElse("");
    List<String> imports = extractImports(content);
    List<String> annotations = extractAnnotations(content);
    List<CodeSymbol> symbols = extractSymbols(content, absolutePath, language, packageName);
    return new FileStructuralMetadata(imports, annotations, symbols);
  }

  @Override
  public StructuralMetadata forChunkSpan(
      FileStructuralMetadata file, int startLine, int endLine) {
    return StructuralMetadata.forChunk(file, startLine, endLine);
  }

  public static Optional<String> extractPackage(String content) {
    Matcher matcher = PACKAGE_PATTERN.matcher(content);
    if (matcher.find()) {
      return Optional.of(matcher.group(1));
    }
    return Optional.empty();
  }

  static List<String> extractImports(String content) {
    List<String> imports = new ArrayList<>();
    Matcher matcher = IMPORT_PATTERN.matcher(content);
    while (matcher.find()) {
      imports.add(matcher.group(1));
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
      String content, String absolutePath, String language, String packageName) {
    List<CodeSymbol> symbols = new ArrayList<>();
    Matcher typeMatcher = TYPE_DECLARATION_PATTERN.matcher(content);
    while (typeMatcher.find()) {
      String kind = typeMatcher.group(1);
      String typeName = typeMatcher.group(2);
      SymbolType symbolType = toSymbolType(kind);
      String qualifiedName = qualifiedName(packageName, typeName);
      int lineStart = lineNumber(content, typeMatcher.start());
      int lineEnd = findTypeEndLine(content, typeMatcher.start(), lineStart);
      symbols.add(
          new CodeSymbol(
              SymbolIdGenerator.javaType(qualifiedName),
              typeName,
              symbolType,
              absolutePath,
              lineStart,
              lineEnd,
              language,
              null));
    }

    Optional<String> primaryClass = JavaMethodExtractor.findClassName(content);
    String containerName = primaryClass.orElse("");
    String qualifiedClass =
        containerName.isBlank()
            ? packageName
            : qualifiedName(packageName, containerName);

    for (JavaMethodExtractor.MethodSpan method : JavaMethodExtractor.extractMethods(content)) {
      symbols.add(
          new CodeSymbol(
              SymbolIdGenerator.javaMethod(qualifiedClass, method.methodName()),
              method.methodName(),
              SymbolType.METHOD,
              absolutePath,
              method.startLine(),
              method.endLine(),
              language,
              containerName.isBlank() ? null : containerName));
    }

    if (!qualifiedClass.isBlank()) {
      for (FieldSpan field : extractFields(content)) {
        symbols.add(
            new CodeSymbol(
                SymbolIdGenerator.javaField(qualifiedClass, field.fieldName()),
                field.fieldName(),
                SymbolType.FIELD,
                absolutePath,
                field.lineStart(),
                field.lineEnd(),
                language,
                containerName.isBlank() ? null : containerName));
      }
    }
    return symbols;
  }

  static List<FieldSpan> extractFields(String content) {
    List<FieldSpan> fields = new ArrayList<>();
    Matcher matcher = FIELD_PATTERN.matcher(content);
    while (matcher.find()) {
      String typePart = matcher.group(1).trim();
      String fieldName = matcher.group(2);
      if (typePart.isBlank()
          || JAVA_FIELD_SKIP.contains(fieldName)
          || typePart.contains("(")
          || matcher.group(0).contains("(")) {
        continue;
      }
      int lineStart = lineNumber(content, matcher.start());
      fields.add(new FieldSpan(fieldName, lineStart, lineStart));
    }
    return fields;
  }

  private static final Set<String> JAVA_FIELD_SKIP =
      Set.of("class", "interface", "enum", "record", "return", "throw", "if", "for", "while");

  record FieldSpan(String fieldName, int lineStart, int lineEnd) {}

  private static SymbolType toSymbolType(String kind) {
    return switch (kind) {
      case "interface" -> SymbolType.INTERFACE;
      case "enum" -> SymbolType.ENUM;
      case "record" -> SymbolType.RECORD;
      default -> SymbolType.CLASS;
    };
  }

  private static String qualifiedName(String packageName, String simpleName) {
    if (packageName == null || packageName.isBlank()) {
      return simpleName;
    }
    return packageName + "." + simpleName;
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

  private static int findTypeEndLine(String content, int declarationStart, int startLine) {
    int braceDepth = 0;
    boolean started = false;
    String[] lines = content.split("\n", -1);
    int startIndex = lineNumber(content, declarationStart) - 1;
    for (int i = startIndex; i < lines.length; i++) {
      for (int j = 0; j < lines[i].length(); j++) {
        char ch = lines[i].charAt(j);
        if (ch == '{') {
          braceDepth++;
          started = true;
        } else if (ch == '}') {
          braceDepth--;
          if (started && braceDepth == 0) {
            return i + 1;
          }
        }
      }
    }
    return Math.max(startLine, lines.length);
  }
}
