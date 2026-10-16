package com.andromedia.indexing;

import java.util.Set;

/**
 * Directories that never hold source worth indexing.
 *
 * <p>Shared by the Lucene writer and the symbol-registry prepass so the two
 * cannot drift: if one skipped {@code dist} and the other did not, symbols would
 * be registered for files that are never indexed, and search results would
 * disagree with {@code andro usages}.
 *
 * <p>Missing entries are not harmless. A project with a {@code dist/} folder
 * (Vite, Next) would have minified bundles indexed alongside real source, where
 * they outrank it in search and crowd out results.
 */
final class SkippedDirectories {

  static final Set<String> NAMES =
      Set.of(
          // VCS metadata
          ".git",
          ".svn",
          ".hg",
          ".andromedia",
          // Build output
          "target",
          "build",
          "dist",
          "out",
          "output",
          "bin",
          "obj",
          ".next",
          ".nuxt",
          ".output",
          ".turbo",
          ".parcel-cache",
          // Dependencies
          "node_modules",
          "vendor",
          "bower_components",
          ".venv",
          "venv",
          "__pycache__",
          ".tox",
          ".mypy_cache",
          ".pytest_cache",
          "Pods",
          "DerivedData",
          // Reports
          "coverage",
          ".nyc_output",
          // Tooling caches and editor state
          ".idea",
          ".vscode",
          ".gradle",
          ".cache");

  private SkippedDirectories() {}
}
