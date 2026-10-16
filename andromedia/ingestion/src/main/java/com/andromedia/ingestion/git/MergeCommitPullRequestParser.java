package com.andromedia.ingestion.git;

import com.andromedia.common.PullRequestMetadata;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MergeCommitPullRequestParser implements PullRequestMetadataProvider {

  private static final Pattern MERGE = Pattern.compile("Merge pull request #(\\d+) from");
  private static final Pattern SQUASH = Pattern.compile("\\(#(\\d+)\\)\\s*$");

  @Override
  public Optional<PullRequestMetadata> resolve(String subject, String body) {
    if (subject == null || subject.isBlank()) {
      return Optional.empty();
    }

    Matcher mergeMatcher = MERGE.matcher(subject);
    if (mergeMatcher.find()) {
      int number = Integer.parseInt(mergeMatcher.group(1));
      return Optional.of(
          new PullRequestMetadata(number, subject, body == null ? "" : body, "github-merge"));
    }

    Matcher squashMatcher = SQUASH.matcher(subject);
    if (squashMatcher.find()) {
      int number = Integer.parseInt(squashMatcher.group(1));
      String title = subject.substring(0, squashMatcher.start()).trim();
      return Optional.of(
          new PullRequestMetadata(number, title, body == null ? "" : body, "github-squash"));
    }

    return Optional.empty();
  }
}
