package com.andromedia.search;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PassThroughReranker implements Reranker {

  @Override
  public List<SearchHit> rerank(String query, List<SearchHit> candidates) {
    return candidates;
  }
}
