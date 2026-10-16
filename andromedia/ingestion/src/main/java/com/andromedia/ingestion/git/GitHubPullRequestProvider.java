package com.andromedia.ingestion.git;

import com.andromedia.common.PullRequestMetadata;
import java.util.Optional;

/**
 * TODO: Fetch PR title and description from GitHub REST API when {@code andromedia.git.github.token}
 * is configured.
 */
public final class GitHubPullRequestProvider implements PullRequestMetadataProvider {

  @Override
  public Optional<PullRequestMetadata> resolve(String subject, String body) {
    return Optional.empty();
  }
}
