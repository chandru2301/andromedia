package com.andromedia.core.trial;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "andromedia.trial")
public class TrialProperties {

  private boolean enabled = false;
  private int maxQueriesPerSession = 20;
  private int sessionTtlHours = 168;
  private String chatModel = "anthropic/claude-3-haiku";
  private int maxStartsPerIpHour = 10;
  /** When true (default in trial mode config), data routes require a Clerk Bearer JWT. */
  private boolean requireClerkJwt = true;
  /** e.g. https://xxx.clerk.accounts.dev/.well-known/jwks.json */
  private String clerkJwksUrl = "";
  /** Optional issuer check, e.g. https://xxx.clerk.accounts.dev */
  private String clerkIssuer = "";
  /** DynamoDB table name; blank = in-memory only (local/dev). */
  private String dynamodbTable = "";
  private String dynamodbRegion = "ap-south-1";

  public boolean enabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int maxQueriesPerSession() {
    return maxQueriesPerSession;
  }

  public void setMaxQueriesPerSession(int maxQueriesPerSession) {
    this.maxQueriesPerSession = maxQueriesPerSession;
  }

  public int sessionTtlHours() {
    return sessionTtlHours;
  }

  public void setSessionTtlHours(int sessionTtlHours) {
    this.sessionTtlHours = sessionTtlHours;
  }

  public String chatModel() {
    return chatModel;
  }

  public void setChatModel(String chatModel) {
    this.chatModel = chatModel == null ? "" : chatModel;
  }

  public int maxStartsPerIpHour() {
    return maxStartsPerIpHour;
  }

  public void setMaxStartsPerIpHour(int maxStartsPerIpHour) {
    this.maxStartsPerIpHour = maxStartsPerIpHour;
  }

  public boolean requireClerkJwt() {
    return requireClerkJwt;
  }

  public void setRequireClerkJwt(boolean requireClerkJwt) {
    this.requireClerkJwt = requireClerkJwt;
  }

  public String clerkJwksUrl() {
    return clerkJwksUrl;
  }

  public void setClerkJwksUrl(String clerkJwksUrl) {
    this.clerkJwksUrl = clerkJwksUrl == null ? "" : clerkJwksUrl;
  }

  public String clerkIssuer() {
    return clerkIssuer;
  }

  public void setClerkIssuer(String clerkIssuer) {
    this.clerkIssuer = clerkIssuer == null ? "" : clerkIssuer;
  }

  public String dynamodbTable() {
    return dynamodbTable;
  }

  public void setDynamodbTable(String dynamodbTable) {
    this.dynamodbTable = dynamodbTable == null ? "" : dynamodbTable;
  }

  public String dynamodbRegion() {
    return dynamodbRegion;
  }

  public void setDynamodbRegion(String dynamodbRegion) {
    this.dynamodbRegion = dynamodbRegion == null || dynamodbRegion.isBlank() ? "ap-south-1" : dynamodbRegion;
  }

  public boolean dynamodbEnabled() {
    return dynamodbTable != null && !dynamodbTable.isBlank();
  }
}
