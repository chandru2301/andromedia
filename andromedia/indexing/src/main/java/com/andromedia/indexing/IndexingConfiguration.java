package com.andromedia.indexing;

import com.andromedia.common.GitHistoryCursorStore;
import com.andromedia.common.JiraHistoryCursorStore;
import com.andromedia.common.LinearHistoryCursorStore;
import com.andromedia.common.NotionHistoryCursorStore;
import com.andromedia.common.SlackHistoryCursorStore;
import com.andromedia.common.ZohoSyncWatermarkStore;
import com.andromedia.ingestion.ChunkingService;
import com.andromedia.ingestion.SemanticBoundaryChunker;
import com.andromedia.ingestion.metadata.MetadataExtractorRegistry;
import com.andromedia.ingestion.references.ReferenceResolverRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IndexingConfiguration {

  @Bean
  MetadataExtractorRegistry metadataExtractorRegistry() {
    return new MetadataExtractorRegistry();
  }

  @Bean
  ReferenceResolverRegistry referenceResolverRegistry() {
    return new ReferenceResolverRegistry();
  }

  @Bean
  SymbolRegistryBuilder symbolRegistryBuilder(MetadataExtractorRegistry metadataExtractorRegistry) {
    return new SymbolRegistryBuilder(metadataExtractorRegistry);
  }

  @Bean
  ChunkingService chunkingService(MetadataExtractorRegistry metadataExtractorRegistry) {
    return new SemanticBoundaryChunker(metadataExtractorRegistry);
  }

  @Bean
  GitHistoryCursorStore gitHistoryCursorStore() {
    return new GitHistoryCursorStore();
  }

  @Bean
  SlackHistoryCursorStore slackHistoryCursorStore() {
    return new SlackHistoryCursorStore();
  }

  @Bean
  ZohoSyncWatermarkStore zohoSyncWatermarkStore() {
    return new ZohoSyncWatermarkStore();
  }

  @Bean
  NotionHistoryCursorStore notionHistoryCursorStore() {
    return new NotionHistoryCursorStore();
  }

  @Bean
  JiraHistoryCursorStore jiraHistoryCursorStore() {
    return new JiraHistoryCursorStore();
  }

  @Bean
  LinearHistoryCursorStore linearHistoryCursorStore() {
    return new LinearHistoryCursorStore();
  }

  @Bean
  GitHistoryIndexingOrchestrator gitHistoryIndexingOrchestrator(
      IndexingService indexingService, GitHistoryCursorStore gitHistoryCursorStore) {
    return new GitHistoryIndexingOrchestrator(indexingService, gitHistoryCursorStore);
  }
}
