package com.andromedia.common;

import java.util.Set;

public final class IndexConstants {

  public static final String INDEX_PATH_PROPERTY = "andromedia.index.path";
  public static final int PROJECT_ID_HEX_LENGTH = 8;

  public static final String FIELD_CHUNK_ID = "chunkId";
  public static final String FIELD_PATH = "path";
  public static final String FIELD_FILE_NAME = "fileName";
  public static final String FIELD_EXTENSION = "extension";
  public static final String FIELD_LANGUAGE = "language";
  public static final String FIELD_CONTENT = "content";
  public static final String FIELD_CLASS_NAME = "className";
  public static final String FIELD_METHOD_NAME = "methodName";
  public static final String FIELD_UNIT_TYPE = "unitType";
  public static final String FIELD_START_LINE = "startLine";
  public static final String FIELD_END_LINE = "endLine";
  public static final String FIELD_EMBEDDING = "embedding";
  public static final String FIELD_IMPORTS = "imports";
  public static final String FIELD_ANNOTATIONS = "annotations";
  public static final String FIELD_SYMBOL_IDS = "symbolIds";
  public static final String FIELD_SYMBOL_NAMES = "symbolNames";
  public static final String FIELD_SYMBOL_TYPES = "symbolTypes";
  public static final String FIELD_REFERENCED_SYMBOL_IDS = "referencedSymbolIds";
  public static final String FIELD_SOURCE_TYPE = "sourceType";
  public static final String FIELD_COMMIT_SHA = "commitSha";
  public static final String FIELD_TIMESTAMP = "timestamp";
  public static final String FIELD_FILE_PATHS = "filePaths";
  public static final String FIELD_AUTHOR = "author";
  public static final String FIELD_CHANNEL_ID = "channelId";
  public static final String FIELD_CHANNEL_NAME = "channelName";
  public static final String FIELD_THREAD_TS = "threadTs";
  public static final String FIELD_PARTICIPANTS = "participants";
  public static final String FIELD_REACTION_COUNT = "reactionCount";
  public static final String FIELD_PAGE_ID = "pageId";
  public static final String FIELD_PAGE_TITLE = "pageTitle";
  public static final String FIELD_LAST_EDITED = "lastEdited";
  public static final String FIELD_ISSUE_KEY = "issueKey";
  public static final String FIELD_ISSUE_TYPE = "issueType";
  public static final String FIELD_PROJECT_KEY = "projectKey";
  public static final String FIELD_PRIORITY = "priority";
  public static final String FIELD_STATUS = "status";
  public static final String FIELD_ISSUE_IDENTIFIER = "issueIdentifier";
  public static final String FIELD_TEAM = "team";
  public static final String FIELD_CYCLE = "cycle";
  public static final String FIELD_STATE = "state";
  public static final String FIELD_DEAL_NAME = "dealName";
  public static final String FIELD_DEAL_STAGE = "dealStage";
  public static final String FIELD_DEAL_AMOUNT = "dealAmount";
  public static final String FIELD_EXPECTED_REVENUE = "expectedRevenue";
  public static final String FIELD_PROBABILITY = "probability";
  public static final String FIELD_CLOSING_DATE = "closingDate";
  public static final String FIELD_DEAL_OWNER = "dealOwner";
  public static final String FIELD_LEAD_SOURCE = "leadSource";
  public static final String FIELD_NEXT_STEP = "nextStep";
  public static final String FIELD_ACCOUNT_NAME = "accountName";
  public static final String FIELD_ACCOUNT_DESCRIPTION = "accountDescription";
  public static final String FIELD_ENTITY_TYPE = "entityType";
  public static final String FIELD_QUALITY_SCORE = "qualityScore";
  public static final String FIELD_QUALITY_SIGNALS = "qualitySignals";
  public static final String FIELD_ZOHO_CONTACTS = "zohoContacts";
  public static final String FIELD_ZOHO_NOTES = "zohoNotes";

  public static final String SOURCE_TYPE_CODE = "CODE";
  public static final String SOURCE_TYPE_GIT = "GIT";
  public static final String SOURCE_TYPE_SLACK = "SLACK";
  public static final String SOURCE_TYPE_NOTION = "NOTION";
  public static final String SOURCE_TYPE_JIRA = "JIRA";
  public static final String SOURCE_TYPE_LINEAR = "LINEAR";
  public static final String SOURCE_TYPE_ZOHO = "ZOHO";
  public static final String SOURCE_TYPE_GOOGLE_CHAT = "GOOGLE_CHAT";

  public static final Set<String> DEFAULT_EXTENSIONS =
      Set.of(
          "java", "kt", "kts", "groovy", "py", "go", "ts", "tsx", "js", "jsx", "sql", "yml",
          "yaml");

  private IndexConstants() {}
}
