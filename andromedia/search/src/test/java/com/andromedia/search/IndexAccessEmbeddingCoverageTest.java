package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.andromedia.common.IndexConstants;
import com.andromedia.llm.EmbeddingService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexAccessEmbeddingCoverageTest {

  @TempDir Path tempDir;

  @Test
  void embeddingCoverageDetectsPartialAcrossPaths() throws Exception {
    Path withVectors = tempDir.resolve("with-vectors");
    Path withoutVectors = tempDir.resolve("without-vectors");
    writeIndex(withVectors, true);
    writeIndex(withoutVectors, false);

    assertEquals(
        IndexAccess.EmbeddingCoverage.PARTIAL,
        IndexAccess.embeddingCoverage(List.of(withoutVectors, withVectors)));
    assertEquals(
        IndexAccess.EmbeddingCoverage.FULL, IndexAccess.embeddingCoverage(List.of(withVectors)));
    assertEquals(
        IndexAccess.EmbeddingCoverage.NONE,
        IndexAccess.embeddingCoverage(List.of(withoutVectors)));
  }

  @Test
  void hybridServiceUsesMixedWeightsWhenCoveragePartial() throws Exception {
    Path withVectors = tempDir.resolve("embedded");
    Path withoutVectors = tempDir.resolve("plain");
    writeIndex(withVectors, true);
    writeIndex(withoutVectors, false);

    HybridSearchService service =
        new HybridSearchService(
            withVectors.toString(),
            new LuceneSearchService(withVectors.toString()),
            new VectorSearchService(withVectors.toString(), new NoOpEmbeddingService()),
            new PassthroughReranker());

    RankingHeuristicsProperties.ChannelWeights weights =
        service.resolveChannelWeights(
            QueryType.NATURAL_LANGUAGE, List.of(withoutVectors, withVectors));

    RankingHeuristicsProperties props = new RankingHeuristicsProperties();
    RankingHeuristicsProperties.ChannelWeights mixed = props.channelWeightsFor(QueryType.MIXED);
    assertEquals(mixed.bm25Weight(), weights.bm25Weight());
    assertEquals(mixed.semanticWeight(), weights.semanticWeight());
  }

  private static void writeIndex(Path path, boolean withEmbedding) throws Exception {
    Files.createDirectories(path);
    try (FSDirectory directory = FSDirectory.open(path);
        IndexWriter writer =
            new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
      Document document = new Document();
      document.add(new StringField("chunkId", "c1", Field.Store.YES));
      document.add(new StoredField("content", "hello"));
      if (withEmbedding) {
        document.add(
            new KnnFloatVectorField(
                IndexConstants.FIELD_EMBEDDING,
                new float[] {0.1f, 0.2f, 0.3f},
                VectorSimilarityFunction.COSINE));
      }
      writer.addDocument(document);
      writer.commit();
    }
  }

  private static final class NoOpEmbeddingService implements EmbeddingService {
    @Override
    public float[] embed(String text) {
      return new float[] {0.1f, 0.2f, 0.3f};
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
      return texts.stream().map(t -> new float[] {0.1f, 0.2f, 0.3f}).toList();
    }

    @Override
    public int dimensions() {
      return 3;
    }
  }

  private static final class PassthroughReranker implements Reranker {
    @Override
    public List<SearchHit> rerank(String query, List<SearchHit> hits) {
      return hits;
    }
  }
}
