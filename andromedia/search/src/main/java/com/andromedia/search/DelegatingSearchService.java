package com.andromedia.search;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
public class DelegatingSearchService implements SearchService {

  private final LuceneSearchService bm25SearchService;
  private final VectorSearchService vectorSearchService;
  private final HybridSearchService hybridSearchService;

  public DelegatingSearchService(
      LuceneSearchService bm25SearchService,
      VectorSearchService vectorSearchService,
      HybridSearchService hybridSearchService) {
    this.bm25SearchService = bm25SearchService;
    this.vectorSearchService = vectorSearchService;
    this.hybridSearchService = hybridSearchService;
  }

  @Override
  public SearchResult search(SearchRequest request) {
    return switch (request.mode()) {
      case SEMANTIC -> vectorSearchService.search(request);
      case HYBRID -> hybridSearchService.search(request);
      case BM25 -> bm25SearchService.search(request);
    };
  }
}
