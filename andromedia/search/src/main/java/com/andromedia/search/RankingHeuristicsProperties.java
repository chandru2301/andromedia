package com.andromedia.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "andromedia.ranking")
public class RankingHeuristicsProperties {

  private double testPenalty = 0.75;
  private double generatedPenalty = 0.5;
  private double configPenalty = 0.85;
  private double mainSourceBoost = 1.15;
  private double serviceListenerConsumerBoost = 1.15;
  private double filenameExactBoost = 1.6;
  private double symbolExactBoost = 1.35;
  private double semanticMinScore = 0.05;

  private double identifierBm25Weight = 1.0;
  private double identifierSemanticWeight = 0.3;
  private double naturalLanguageBm25Weight = 0.6;
  private double naturalLanguageSemanticWeight = 1.0;
  private double mixedBm25Weight = 0.85;
  private double mixedSemanticWeight = 0.85;
  private double gitHistoryBoost = 2.0;
  private double slackDecisionBoost = 2.5;
  private double notionArchitectureBoost = 2.0;
  private double jiraTicketBoost = 2.0;
  private double linearTicketBoost = 2.0;
  private double zohoCrmBoost = 2.0;

  public double testPenalty() {
    return testPenalty;
  }

  public void setTestPenalty(double testPenalty) {
    this.testPenalty = testPenalty;
  }

  public double generatedPenalty() {
    return generatedPenalty;
  }

  public void setGeneratedPenalty(double generatedPenalty) {
    this.generatedPenalty = generatedPenalty;
  }

  public double configPenalty() {
    return configPenalty;
  }

  public void setConfigPenalty(double configPenalty) {
    this.configPenalty = configPenalty;
  }

  public double mainSourceBoost() {
    return mainSourceBoost;
  }

  public void setMainSourceBoost(double mainSourceBoost) {
    this.mainSourceBoost = mainSourceBoost;
  }

  public double serviceListenerConsumerBoost() {
    return serviceListenerConsumerBoost;
  }

  public void setServiceListenerConsumerBoost(double serviceListenerConsumerBoost) {
    this.serviceListenerConsumerBoost = serviceListenerConsumerBoost;
  }

  public double filenameExactBoost() {
    return filenameExactBoost;
  }

  public void setFilenameExactBoost(double filenameExactBoost) {
    this.filenameExactBoost = filenameExactBoost;
  }

  public double symbolExactBoost() {
    return symbolExactBoost;
  }

  public void setSymbolExactBoost(double symbolExactBoost) {
    this.symbolExactBoost = symbolExactBoost;
  }

  public double semanticMinScore() {
    return semanticMinScore;
  }

  public void setSemanticMinScore(double semanticMinScore) {
    this.semanticMinScore = semanticMinScore;
  }

  public double identifierBm25Weight() {
    return identifierBm25Weight;
  }

  public void setIdentifierBm25Weight(double identifierBm25Weight) {
    this.identifierBm25Weight = identifierBm25Weight;
  }

  public double identifierSemanticWeight() {
    return identifierSemanticWeight;
  }

  public void setIdentifierSemanticWeight(double identifierSemanticWeight) {
    this.identifierSemanticWeight = identifierSemanticWeight;
  }

  public double naturalLanguageBm25Weight() {
    return naturalLanguageBm25Weight;
  }

  public void setNaturalLanguageBm25Weight(double naturalLanguageBm25Weight) {
    this.naturalLanguageBm25Weight = naturalLanguageBm25Weight;
  }

  public double naturalLanguageSemanticWeight() {
    return naturalLanguageSemanticWeight;
  }

  public void setNaturalLanguageSemanticWeight(double naturalLanguageSemanticWeight) {
    this.naturalLanguageSemanticWeight = naturalLanguageSemanticWeight;
  }

  public double mixedBm25Weight() {
    return mixedBm25Weight;
  }

  public void setMixedBm25Weight(double mixedBm25Weight) {
    this.mixedBm25Weight = mixedBm25Weight;
  }

  public double mixedSemanticWeight() {
    return mixedSemanticWeight;
  }

  public void setMixedSemanticWeight(double mixedSemanticWeight) {
    this.mixedSemanticWeight = mixedSemanticWeight;
  }

  public double gitHistoryBoost() {
    return gitHistoryBoost;
  }

  public void setGitHistoryBoost(double gitHistoryBoost) {
    this.gitHistoryBoost = gitHistoryBoost;
  }

  public double slackDecisionBoost() {
    return slackDecisionBoost;
  }

  public void setSlackDecisionBoost(double slackDecisionBoost) {
    this.slackDecisionBoost = slackDecisionBoost;
  }

  public double notionArchitectureBoost() {
    return notionArchitectureBoost;
  }

  public void setNotionArchitectureBoost(double notionArchitectureBoost) {
    this.notionArchitectureBoost = notionArchitectureBoost;
  }

  public double jiraTicketBoost() {
    return jiraTicketBoost;
  }

  public void setJiraTicketBoost(double jiraTicketBoost) {
    this.jiraTicketBoost = jiraTicketBoost;
  }

  public double linearTicketBoost() {
    return linearTicketBoost;
  }

  public void setLinearTicketBoost(double linearTicketBoost) {
    this.linearTicketBoost = linearTicketBoost;
  }

  public double zohoCrmBoost() {
    return zohoCrmBoost;
  }

  public void setZohoCrmBoost(double zohoCrmBoost) {
    this.zohoCrmBoost = zohoCrmBoost;
  }

  public ChannelWeights channelWeightsFor(QueryType queryType) {
    return switch (queryType) {
      case IDENTIFIER -> new ChannelWeights(identifierBm25Weight, identifierSemanticWeight);
      case NATURAL_LANGUAGE ->
          new ChannelWeights(naturalLanguageBm25Weight, naturalLanguageSemanticWeight);
      case MIXED -> new ChannelWeights(mixedBm25Weight, mixedSemanticWeight);
    };
  }

  public record ChannelWeights(double bm25Weight, double semanticWeight) {}
}
