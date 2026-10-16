package com.andromedia.indexing;

import java.util.Map;

final class LanguageResolver {

  private static final Map<String, String> EXTENSION_TO_LANGUAGE =
      Map.ofEntries(
          Map.entry("java", "java"),
          Map.entry("kt", "kotlin"),
          Map.entry("kts", "kotlin"),
          Map.entry("groovy", "groovy"),
          Map.entry("ts", "typescript"),
          Map.entry("tsx", "typescript"),
          Map.entry("js", "javascript"),
          Map.entry("jsx", "javascript"),
          Map.entry("py", "python"),
          Map.entry("go", "go"),
          Map.entry("rs", "rust"),
          Map.entry("cs", "csharp"),
          Map.entry("cpp", "cpp"),
          Map.entry("c", "c"),
          Map.entry("h", "c"),
          Map.entry("hpp", "cpp"),
          Map.entry("scala", "scala"),
          Map.entry("rb", "ruby"),
          Map.entry("php", "php"),
          Map.entry("sql", "sql"),
          Map.entry("md", "markdown"),
          Map.entry("xml", "xml"),
          Map.entry("json", "json"),
          Map.entry("yaml", "yaml"),
          Map.entry("yml", "yaml"));

  private LanguageResolver() {}

  static String resolve(String extension) {
    return EXTENSION_TO_LANGUAGE.getOrDefault(extension.toLowerCase(), extension.toLowerCase());
  }
}
