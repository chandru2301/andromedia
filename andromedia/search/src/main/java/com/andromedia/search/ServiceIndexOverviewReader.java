package com.andromedia.search;

import com.andromedia.common.IndexConstants;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

/**
 * Disk-backed overview of a Lucene service index (chunk count, source types, size, last sync).
 */
public final class ServiceIndexOverviewReader {

  private ServiceIndexOverviewReader() {}

  public record Overview(
      String indexPath,
      boolean exists,
      int totalChunks,
      int sourcesConnected,
      List<String> sourceTypes,
      Instant lastSyncAt,
      long indexSizeBytes) {}

  public static Overview read(Path indexPath) {
    Path normalized = indexPath.toAbsolutePath().normalize();
    if (!Files.isDirectory(normalized)) {
      return empty(normalized);
    }

    DirectoryStats dirStats = walkDirectory(normalized);
    if (!dirStats.hasLuceneFiles) {
      return new Overview(
          normalized.toString(), false, 0, 0, List.of(), dirStats.lastModified, dirStats.sizeBytes);
    }

    try (Directory directory = FSDirectory.open(normalized)) {
      if (!DirectoryReader.indexExists(directory)) {
        return new Overview(
            normalized.toString(),
            false,
            0,
            0,
            List.of(),
            dirStats.lastModified,
            dirStats.sizeBytes);
      }
      try (DirectoryReader reader = DirectoryReader.open(directory)) {
        int totalChunks = reader.numDocs();
        List<String> sourceTypes = resolveSourceTypes(reader, totalChunks);
        return new Overview(
            normalized.toString(),
            true,
            totalChunks,
            sourceTypes.size(),
            sourceTypes,
            dirStats.lastModified,
            dirStats.sizeBytes);
      }
    } catch (IOException ex) {
      throw new SearchException("Failed to read index overview at: " + normalized, ex);
    }
  }

  private static Overview empty(Path indexPath) {
    return new Overview(indexPath.toString(), false, 0, 0, List.of(), null, 0L);
  }

  private static List<String> resolveSourceTypes(DirectoryReader reader, int totalChunks)
      throws IOException {
    Set<String> types = new LinkedHashSet<>();
    long docsWithSourceType = 0;
    Terms terms = MultiTerms.getTerms(reader, IndexConstants.FIELD_SOURCE_TYPE);
    if (terms != null) {
      TermsEnum termsEnum = terms.iterator();
      BytesRef term;
      while ((term = termsEnum.next()) != null) {
        String value = term.utf8ToString();
        if (value != null && !value.isBlank()) {
          types.add(value);
        }
        docsWithSourceType += termsEnum.docFreq();
      }
    }

    if (totalChunks > 0 && (docsWithSourceType < totalChunks || types.isEmpty())) {
      types.add(IndexConstants.SOURCE_TYPE_CODE);
    }

    return new ArrayList<>(types);
  }

  private static DirectoryStats walkDirectory(Path indexPath) {
    long[] sizeBytes = {0L};
    long[] lastModifiedMillis = {0L};
    boolean[] hasLuceneFiles = {false};

    try {
      Files.walkFileTree(
          indexPath,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              String name = file.getFileName().toString();
              if ("write.lock".equals(name)) {
                return FileVisitResult.CONTINUE;
              }
              if (attrs.isRegularFile()) {
                sizeBytes[0] += attrs.size();
                lastModifiedMillis[0] = Math.max(lastModifiedMillis[0], attrs.lastModifiedTime().toMillis());
                if (name.startsWith("segments") || name.endsWith(".si") || name.contains("Lucene")) {
                  hasLuceneFiles[0] = true;
                }
              }
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException ex) {
      throw new SearchException("Failed to inspect index directory at: " + indexPath, ex);
    }

    Instant lastModified =
        lastModifiedMillis[0] > 0 ? Instant.ofEpochMilli(lastModifiedMillis[0]) : null;
    return new DirectoryStats(sizeBytes[0], lastModified, hasLuceneFiles[0]);
  }

  private record DirectoryStats(long sizeBytes, Instant lastModified, boolean hasLuceneFiles) {}
}
