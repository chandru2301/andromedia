package com.andromedia.indexing;

public interface IndexingService {

  IndexingStats indexPath(IndexingRequest request);

  GitHistoryIndexingStats indexGitHistory(GitHistoryIndexingRequest request);

  SlackHistoryIndexingStats indexSlackHistory(SlackHistoryIndexingRequest request);

  NotionHistoryIndexingStats indexNotionHistory(NotionHistoryIndexingRequest request);

  JiraHistoryIndexingStats indexJiraHistory(JiraHistoryIndexingRequest request);

  LinearHistoryIndexingStats indexLinearHistory(LinearHistoryIndexingRequest request);

  ZohoHistoryIndexingStats indexZohoHistory(ZohoHistoryIndexingRequest request);

  GoogleChatHistoryIndexingStats indexGoogleChatHistory(GoogleChatHistoryIndexingRequest request);
}
