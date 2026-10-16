package com.andromedia.ingestion.git;

import com.andromedia.common.ChangedSymbol;
import com.andromedia.common.SymbolIdGenerator;
import com.andromedia.ingestion.JavaMethodExtractor;
import com.andromedia.ingestion.metadata.JavaMetadataExtractor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class GitDiffSymbolResolver {

  private GitDiffSymbolResolver() {}

  public static List<ChangedSymbol> resolveSymbols(
      String filePath, String extension, String fileContent, List<LineRange> changedLineRanges) {
    if (fileContent == null || fileContent.isBlank()) {
      return List.of(fileLevelSymbol(filePath));
    }
    if ("java".equals(extension)) {
      return resolveJavaSymbols(filePath, fileContent, changedLineRanges);
    }
    return List.of(fileLevelSymbol(filePath));
  }

  private static List<ChangedSymbol> resolveJavaSymbols(
      String filePath, String fileContent, List<LineRange> changedLineRanges) {
    if (changedLineRanges.isEmpty()) {
      return List.of(fileLevelSymbol(filePath));
    }

    Optional<String> packageName = JavaMetadataExtractor.extractPackage(fileContent);
    Optional<String> className = JavaMethodExtractor.findClassName(fileContent);
    String qualifiedClass =
        className
            .map(name -> qualify(packageName.orElse(""), name))
            .orElseGet(() -> packageName.orElse(stripExtension(Path.of(filePath).getFileName().toString())));

    Set<String> seen = new LinkedHashSet<>();
    List<ChangedSymbol> symbols = new ArrayList<>();

    for (JavaMethodExtractor.MethodSpan method : JavaMethodExtractor.extractMethods(fileContent)) {
      if (!overlapsAny(method.startLine(), method.endLine(), changedLineRanges)) {
        continue;
      }
      String symbolId = SymbolIdGenerator.javaMethod(qualifiedClass, method.methodName());
      String symbolName =
          className.map(c -> c + "#" + method.methodName()).orElse(method.methodName());
      if (seen.add(symbolId)) {
        symbols.add(
            new ChangedSymbol(symbolId, symbolName, filePath, method.startLine(), method.endLine()));
      }
    }

    if (symbols.isEmpty() && className.isPresent()) {
      String typeId = SymbolIdGenerator.javaType(qualifiedClass);
      if (seen.add(typeId)) {
        symbols.add(new ChangedSymbol(typeId, className.get(), filePath, 1, fileContent.split("\n", -1).length));
      }
    }

    if (symbols.isEmpty()) {
      symbols.add(fileLevelSymbol(filePath));
    }
    return symbols;
  }

  private static ChangedSymbol fileLevelSymbol(String filePath) {
    String fileName = Path.of(filePath).getFileName().toString();
    return new ChangedSymbol(filePath, fileName, filePath, 0, 0);
  }

  private static String qualify(String packageName, String simpleName) {
    if (packageName == null || packageName.isBlank()) {
      return simpleName;
    }
    return packageName + "." + simpleName;
  }

  private static String stripExtension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  private static boolean overlapsAny(int startLine, int endLine, List<LineRange> ranges) {
    for (LineRange range : ranges) {
      if (startLine <= range.endLine() && endLine >= range.startLine()) {
        return true;
      }
    }
    return false;
  }

  public record LineRange(int startLine, int endLine) {}
}
