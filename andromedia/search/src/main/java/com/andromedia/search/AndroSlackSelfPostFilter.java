package com.andromedia.search;

import com.andromedia.common.AndroSlackSelfPosts;
import java.util.ArrayList;
import java.util.List;

/** Drops Andro's own Slack alert/investigate recaps from search results. */
public final class AndroSlackSelfPostFilter {

  private AndroSlackSelfPostFilter() {}

  public static List<SearchHit> remove(List<SearchHit> hits) {
    if (hits == null || hits.isEmpty()) {
      return hits == null ? List.of() : hits;
    }
    List<SearchHit> kept = new ArrayList<>(hits.size());
    for (SearchHit hit : hits) {
      if (hit == null) {
        continue;
      }
      String sourceType = hit.sourceType().orElse("");
      String content = hit.content().orElse("");
      if (AndroSlackSelfPosts.isIndexedSelfPost(sourceType, content)) {
        continue;
      }
      kept.add(hit);
    }
    return kept;
  }
}
