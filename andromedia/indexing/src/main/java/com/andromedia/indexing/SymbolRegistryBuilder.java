package com.andromedia.indexing;

import com.andromedia.common.CodeSymbol;
import com.andromedia.common.ProjectSymbolRegistry;
import com.andromedia.ingestion.metadata.MetadataExtractorRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class SymbolRegistryBuilder {

  private static final Set<String> SKIPPED_DIRECTORY_NAMES = SkippedDirectories.NAMES;


  
  private final MetadataExtractorRegistry metadataExtractorRegistry;

  public SymbolRegistryBuilder(MetadataExtractorRegistry metadataExtractorRegistry) {
    this.metadataExtractorRegistry = metadataExtractorRegistry;
  }

  public ProjectSymbolRegistry build(Path rootDirectory, Set<String> includedExtensions) {
    ProjectSymbolRegistry registry = new ProjectSymbolRegistry();
    for (Path filePath : listIncludedFiles(rootDirectory, includedExtensions)) {
      registerFile(registry, filePath);
    }
    return registry;
  }

  private void registerFile(ProjectSymbolRegistry registry, Path filePath) {
    try {
      String extension = extractExtension(filePath);
      String content = Files.readString(filePath, StandardCharsets.UTF_8);
      String language = LanguageResolver.resolve(extension);
      metadataExtractorRegistry
          .extractFile(filePath, content, language)
          .symbols()
          .forEach(registry::register);
    } catch (IOException ex) {
      throw new IndexingException("Failed to read file for symbol registry: " + filePath, ex);
    }
  }

  private static List<Path> listIncludedFiles(Path rootDirectory, Set<String> includedExtensions) {
    List<Path> files = new ArrayList<>();
    try (var paths = Files.walk(rootDirectory)) {
      paths
          .filter(Files::isRegularFile)
          .filter(filePath -> !shouldSkipPath(filePath, rootDirectory))
          .filter(filePath -> includedExtensions.contains(extractExtension(filePath)))
          .sorted(Comparator.comparing(path -> path.toAbsolutePath().normalize().toString()))
          .forEach(files::add);
    } catch (IOException ex) {
      throw new IndexingException("Failed to walk directory for symbol registry: " + rootDirectory, ex);
    }
    return files;
  }

  static boolean shouldSkipPath(Path filePath, Path rootDirectory) {
    Path normalizedFile = filePath.toAbsolutePath().normalize();
    Path normalizedRoot = rootDirectory.toAbsolutePath().normalize();
    if (!normalizedFile.startsWith(normalizedRoot)) {
      return true;
    }
    for (Path component : normalizedRoot.relativize(normalizedFile)) {
      if (SKIPPED_DIRECTORY_NAMES.contains(component.toString())) {
        return true;
      }
    }
    return false;
  }

  static String extractExtension(Path filePath) {
    String fileName = filePath.getFileName().toString();
    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
      return "";
    }
    return fileName.substring(dotIndex + 1).toLowerCase();
  }
}
