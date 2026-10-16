package com.andromedia.search;

import java.util.List;

public interface Reranker {

  List<SearchHit> rerank(String query, List<SearchHit> candidates);
}
