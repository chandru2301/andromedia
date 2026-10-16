package com.andromedia.ingestion.git;

import com.andromedia.common.PullRequestMetadata;
import java.util.Optional;

public interface PullRequestMetadataProvider {

  Optional<PullRequestMetadata> resolve(String subject, String body);
}
