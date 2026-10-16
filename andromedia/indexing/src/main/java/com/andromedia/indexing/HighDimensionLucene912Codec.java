package com.andromedia.indexing;

import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene912.Lucene912Codec;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat;

final class HighDimensionLucene912Codec extends Lucene912Codec {

  private final int maxVectorDimensions;

  HighDimensionLucene912Codec(int maxVectorDimensions) {
    this.maxVectorDimensions = maxVectorDimensions;
  }

  @Override
  public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
    return new HighDimensionKnnVectorsFormat(new Lucene99HnswVectorsFormat(), maxVectorDimensions);
  }
}
