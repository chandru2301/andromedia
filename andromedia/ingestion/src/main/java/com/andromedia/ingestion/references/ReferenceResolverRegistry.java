package com.andromedia.ingestion.references;

import com.andromedia.common.CodeSymbol;
import com.andromedia.common.ProjectSymbolRegistry;
import com.andromedia.ingestion.metadata.JavaMetadataExtractor;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ReferenceResolverRegistry {

  private final Map<String, ReferenceResolver> resolvers;

  public ReferenceResolverRegistry() {
    this(
        Map.of(
            "java", new JavaReferenceResolver(),
            "python", new PythonReferenceResolver()));
  }

  ReferenceResolverRegistry(Map<String, ReferenceResolver> resolvers) {
    this.resolvers = Map.copyOf(resolvers);
  }

  public List<String> resolveReferences(
      Path filePath,
      String language,
      String chunkContent,
      int startLine,
      int endLine,
      List<String> imports,
      String fileContent,
      List<CodeSymbol> localSymbols,
      ProjectSymbolRegistry registry) {
    ReferenceResolver resolver = resolvers.get(language == null ? "" : language.toLowerCase());
    if (resolver == null) {
      return List.of();
    }
    String packageOrModule = packageOrModuleName(filePath, language, fileContent);
    return resolver.resolveReferences(
        chunkContent, startLine, endLine, imports, packageOrModule, localSymbols, registry);
  }

  private static String packageOrModuleName(Path filePath, String language, String fileContent) {
    if ("java".equalsIgnoreCase(language)) {
      Optional<String> pkg = JavaMetadataExtractor.extractPackage(fileContent);
      return pkg.orElse("");
    }
    if ("python".equalsIgnoreCase(language)) {
      return PythonReferenceResolver.moduleNameFromPath(filePath);
    }
    return "";
  }
}
