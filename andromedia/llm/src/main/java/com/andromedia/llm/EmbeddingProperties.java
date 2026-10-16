package com.andromedia.llm;

import com.andromedia.common.EmbeddingConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "andromedia.embedding")
public class EmbeddingProperties {

  private String provider = "openrouter";
  private String baseUrl = EmbeddingConstants.DEFAULT_BASE_URL;
  private String model = EmbeddingConstants.DEFAULT_MODEL;
  private int dimensions = EmbeddingConstants.ADA_002_DIMENSIONS;
  private String apiKey = "";
  private String httpReferer = "";
  private String appTitle = "Andromedia";
  private int batchSize = 20;
  private int maxCharsPerBatch = 200_000;

  public String provider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String baseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String model() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public int dimensions() {
    return dimensions;
  }

  public void setDimensions(int dimensions) {
    this.dimensions = dimensions;
  }

  public String apiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey == null ? "" : apiKey;
  }

  public String httpReferer() {
    return httpReferer;
  }

  public void setHttpReferer(String httpReferer) {
    this.httpReferer = httpReferer == null ? "" : httpReferer;
  }

  public String appTitle() {
    return appTitle;
  }

  public void setAppTitle(String appTitle) {
    this.appTitle = appTitle == null ? "" : appTitle;
  }

  public int batchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public int maxCharsPerBatch() {
    return maxCharsPerBatch;
  }

  public void setMaxCharsPerBatch(int maxCharsPerBatch) {
    this.maxCharsPerBatch = maxCharsPerBatch;
  }

  public boolean hasApiKey() {
    return apiKey != null && !apiKey.isBlank();
  }
}
