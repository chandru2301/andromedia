package com.andromedia.ingestion.git;

import com.andromedia.common.ChangeType;
import com.andromedia.common.ChangedFile;
import com.andromedia.common.ChangedSymbol;
import com.andromedia.common.GitChangeEvent;
import com.andromedia.common.IndexConstants;
import com.andromedia.common.PullRequestMetadata;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

public class GitIngestionService {

  private static final Set<String> SKIPPED_DIRECTORY_NAMES =
      Set.of(".git", ".svn", ".hg", "target", "build", "node_modules", ".andromedia", ".idea");

  private final List<PullRequestMetadataProvider> pullRequestProviders;

  public GitIngestionService() {
    this(List.of());
  }

  public GitIngestionService(List<PullRequestMetadataProvider> pullRequestProviders) {
    this.pullRequestProviders =
        pullRequestProviders == null ? List.of() : List.copyOf(pullRequestProviders);
  }

  public static boolean isGitRepository(Path localPath) {
    try (Repository repo = openRepository(localPath)) {
      return repo.getObjectDatabase() != null;
    } catch (IOException ex) {
      return false;
    }
  }

  public List<GitChangeEvent> ingest(Path localPath, GitIngestionOptions options)
      throws IOException {
    GitIngestionOptions effective = options == null ? GitIngestionOptions.defaults() : options;
    try (Repository repo = openRepository(localPath);
        RevWalk walk = new RevWalk(repo);
        Git git = new Git(repo)) {
      Iterable<RevCommit> commits = walkCommits(git, repo, effective);
      List<GitChangeEvent> events = new ArrayList<>();
      for (RevCommit commit : commits) {
        walk.parseBody(commit);
        if (effective.stopAtCommitSha() != null
            && effective.stopAtCommitSha().equals(commit.getName())) {
          break;
        }
        if (effective.since() != null && commitInstant(commit).isBefore(effective.since())) {
          continue;
        }
        events.add(toChangeEvent(repo, walk, commit));
      }
      return events;
    } catch (GitAPIException ex) {
      throw new IOException("Failed to walk git history at " + localPath, ex);
    }
  }

  private static Repository openRepository(Path localPath) throws IOException {
    return new FileRepositoryBuilder().findGitDir(localPath.toFile()).build();
  }

  private static Iterable<RevCommit> walkCommits(
      Git git, Repository repo, GitIngestionOptions options) throws GitAPIException, IOException {
    var logCommand = git.log().setMaxCount(options.maxCommits());
    if (options.branch() != null && !options.branch().isBlank()) {
      ObjectId branchId = repo.resolve("refs/heads/" + options.branch().trim());
      if (branchId == null) {
        branchId = repo.resolve(options.branch().trim());
      }
      if (branchId == null) {
        throw new IOException("Branch not found: " + options.branch());
      }
      logCommand.add(branchId);
    } else {
      logCommand.add(repo.resolve("HEAD"));
    }
    return logCommand.call();
  }

  private GitChangeEvent toChangeEvent(Repository repo, RevWalk walk, RevCommit commit)
      throws IOException {
    String fullMessage = commit.getFullMessage() == null ? "" : commit.getFullMessage();
    String subject = firstLine(fullMessage);
    String body = remainder(fullMessage, subject);
    PersonIdent author = commit.getAuthorIdent();
    String authorName = author == null ? "" : author.getName();
    Instant committedAt = commitInstant(commit);

    List<String> parentShas = new ArrayList<>();
    for (RevCommit parent : commit.getParents()) {
      parentShas.add(parent.getName());
    }

    Optional<PullRequestMetadata> pullRequest = resolvePullRequest(subject, body);

    if (commit.getParentCount() > 1) {
      return new GitChangeEvent(
          commit.getName(),
          committedAt,
          authorName,
          subject,
          body,
          parentShas,
          List.of(),
          List.of(),
          pullRequest);
    }

    List<ChangedFile> changedFiles = new ArrayList<>();
    Map<String, List<GitDiffSymbolResolver.LineRange>> lineRangesByPath = new LinkedHashMap<>();

    try (DiffFormatter diffFormatter = createDiffFormatter(repo)) {
      RevCommit parent =
          commit.getParentCount() > 0 ? walk.parseCommit(commit.getParent(0).getId()) : null;
      List<DiffEntry> diffs =
          parent != null
              ? diffFormatter.scan(parent.getTree(), commit.getTree())
              : diffFormatter.scan(
                  new EmptyTreeIterator(),
                  new CanonicalTreeParser(null, walk.getObjectReader(), commit.getTree()));

      for (DiffEntry diff : diffs) {
        String path = resolvePath(diff);
        if (path == null || shouldSkipPath(path)) {
          continue;
        }
        String extension = extractExtension(path);
        if (!IndexConstants.DEFAULT_EXTENSIONS.contains(extension)) {
          continue;
        }
        changedFiles.add(new ChangedFile(path, toChangeType(diff.getChangeType())));
        if (diff.getChangeType() != DiffEntry.ChangeType.DELETE) {
          lineRangesByPath
              .computeIfAbsent(path, ignored -> new ArrayList<>())
              .addAll(extractChangedLineRanges(diffFormatter, diff));
        }
      }
    }

    List<ChangedSymbol> changedSymbols = resolveChangedSymbols(repo, walk, commit, lineRangesByPath);
    return new GitChangeEvent(
        commit.getName(),
        committedAt,
        authorName,
        subject,
        body,
        parentShas,
        changedFiles,
        changedSymbols,
        pullRequest);
  }

  private Optional<PullRequestMetadata> resolvePullRequest(String subject, String body) {
    for (PullRequestMetadataProvider provider : pullRequestProviders) {
      Optional<PullRequestMetadata> metadata = provider.resolve(subject, body);
      if (metadata.isPresent()) {
        return metadata;
      }
    }
    return Optional.empty();
  }

  private static List<ChangedSymbol> resolveChangedSymbols(
      Repository repo,
      RevWalk walk,
      RevCommit commit,
      Map<String, List<GitDiffSymbolResolver.LineRange>> lineRangesByPath)
      throws IOException {
    Set<String> seen = new LinkedHashSet<>();
    List<ChangedSymbol> symbols = new ArrayList<>();
    for (Map.Entry<String, List<GitDiffSymbolResolver.LineRange>> entry :
        lineRangesByPath.entrySet()) {
      String path = entry.getKey();
      String extension = extractExtension(path);
      String content = readFileAtCommit(repo, walk, commit, path);
      for (ChangedSymbol symbol :
          GitDiffSymbolResolver.resolveSymbols(path, extension, content, entry.getValue())) {
        if (seen.add(symbol.symbolId())) {
          symbols.add(symbol);
        }
      }
    }
    return symbols;
  }

  private static String readFileAtCommit(
      Repository repo, RevWalk walk, RevCommit commit, String path) throws IOException {
    try (var treeWalk = org.eclipse.jgit.treewalk.TreeWalk.forPath(repo, path, commit.getTree())) {
      if (treeWalk == null) {
        return "";
      }
      ObjectId objectId = treeWalk.getObjectId(0);
      ObjectLoader loader = repo.open(objectId);
      return new String(loader.getBytes(), StandardCharsets.UTF_8);
    }
  }

  private static List<GitDiffSymbolResolver.LineRange> extractChangedLineRanges(
      DiffFormatter diffFormatter, DiffEntry diff) throws IOException {
    List<GitDiffSymbolResolver.LineRange> ranges = new ArrayList<>();
    var fileHeader = diffFormatter.toFileHeader(diff);
    for (var hunk : fileHeader.getHunks()) {
      for (Edit edit : hunk.toEditList()) {
        if (edit.getLengthB() <= 0) {
          continue;
        }
        int startLine = edit.getBeginB() + 1;
        int endLine = edit.getEndB();
        ranges.add(new GitDiffSymbolResolver.LineRange(startLine, endLine));
      }
    }
    return ranges;
  }

  private static DiffFormatter createDiffFormatter(Repository repo) {
    DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE);
    diffFormatter.setRepository(repo);
    diffFormatter.setDiffComparator(RawTextComparator.DEFAULT);
    diffFormatter.setDetectRenames(true);
    return diffFormatter;
  }

  private static ChangeType toChangeType(DiffEntry.ChangeType type) {
    return switch (type) {
      case ADD -> ChangeType.ADD;
      case MODIFY -> ChangeType.MODIFY;
      case DELETE -> ChangeType.DELETE;
      case RENAME, COPY -> ChangeType.RENAME;
    };
  }

  private static String resolvePath(DiffEntry diff) {
    DiffEntry.ChangeType changeType = diff.getChangeType();
    if (changeType == DiffEntry.ChangeType.DELETE) {
      return normalizeDiffPath(diff.getOldPath());
    }
    return normalizeDiffPath(diff.getNewPath());
  }

  private static String normalizeDiffPath(String path) {
    if (path == null || DiffEntry.DEV_NULL.equals(path)) {
      return null;
    }
    return path;
  }

  private static boolean shouldSkipPath(String path) {
    for (Path component : Path.of(path)) {
      if (SKIPPED_DIRECTORY_NAMES.contains(component.toString())) {
        return true;
      }
    }
    return false;
  }

  private static String extractExtension(String path) {
    String fileName = Path.of(path).getFileName().toString();
    int dotIndex = fileName.lastIndexOf('.');
    if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
      return "";
    }
    return fileName.substring(dotIndex + 1).toLowerCase();
  }

  private static Instant commitInstant(RevCommit commit) {
    PersonIdent author = commit.getAuthorIdent();
    return author == null ? Instant.EPOCH : author.getWhen().toInstant();
  }

  private static String firstLine(String message) {
    int newline = message.indexOf('\n');
    return newline < 0 ? message.trim() : message.substring(0, newline).trim();
  }

  private static String remainder(String message, String subject) {
    if (message.length() <= subject.length()) {
      return "";
    }
    String remainder = message.substring(subject.length()).trim();
    if (remainder.startsWith("\n")) {
      remainder = remainder.substring(1).trim();
    }
    return remainder;
  }
}
