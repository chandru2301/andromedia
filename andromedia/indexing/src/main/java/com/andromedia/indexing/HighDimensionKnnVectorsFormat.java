package com.andromedia.indexing;

import java.io.IOException;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;

final class HighDimensionKnnVectorsFormat extends KnnVectorsFormat {

  private final KnnVectorsFormat delegate;
  private final int maxDimensions;

  HighDimensionKnnVectorsFormat(KnnVectorsFormat delegate, int maxDimensions) {
    super(delegate.getName());
    this.delegate = delegate;
    this.maxDimensions = maxDimensions;
  }

  @Override
  public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
    return delegate.fieldsWriter(state);
  }

  @Override
  public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
    return delegate.fieldsReader(state);
  }

  @Override
  public int getMaxDimensions(String fieldName) {
    return maxDimensions;
  }
}
