package com.andromedia.search;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "andromedia.zoho.qualification")
public class ZohoQualificationProperties {

  public static final String MODEL_ID = "zoho-qualification-v1";

  private double probabilityWeight = 0.50;
  private double stageWeight = 0.30;
  private double freshnessWeight = 0.20;
  private double stalePenalty = 0.15;
  private double overduePenalty = 0.10;
  private int freshnessHalfLifeDays = 30;
  private int staleAfterDays = 180;
  private List<String> activeStageOrder =
      new ArrayList<>(
          List.of(
              "Qualification",
              "Needs Analysis",
              "Value Proposition",
              "Id. Decision Makers",
              "Proposal/Price Quote",
              "Negotiation/Review"));
  private List<String> closedStageMarkers =
      new ArrayList<>(List.of("Closed Won", "Closed Lost", "Closed"));

  public double probabilityWeight() {
    return probabilityWeight;
  }

  public void setProbabilityWeight(double probabilityWeight) {
    this.probabilityWeight = clampWeight(probabilityWeight, 0.50);
  }

  public double stageWeight() {
    return stageWeight;
  }

  public void setStageWeight(double stageWeight) {
    this.stageWeight = clampWeight(stageWeight, 0.30);
  }

  public double freshnessWeight() {
    return freshnessWeight;
  }

  public void setFreshnessWeight(double freshnessWeight) {
    this.freshnessWeight = clampWeight(freshnessWeight, 0.20);
  }

  public double stalePenalty() {
    return stalePenalty;
  }

  public void setStalePenalty(double stalePenalty) {
    this.stalePenalty = Math.max(0.0, stalePenalty);
  }

  public double overduePenalty() {
    return overduePenalty;
  }

  public void setOverduePenalty(double overduePenalty) {
    this.overduePenalty = Math.max(0.0, overduePenalty);
  }

  public int freshnessHalfLifeDays() {
    return freshnessHalfLifeDays;
  }

  public void setFreshnessHalfLifeDays(int freshnessHalfLifeDays) {
    this.freshnessHalfLifeDays = freshnessHalfLifeDays <= 0 ? 30 : freshnessHalfLifeDays;
  }

  public int staleAfterDays() {
    return staleAfterDays;
  }

  public void setStaleAfterDays(int staleAfterDays) {
    this.staleAfterDays = staleAfterDays <= 0 ? 180 : staleAfterDays;
  }

  public List<String> activeStageOrder() {
    return List.copyOf(activeStageOrder);
  }

  public void setActiveStageOrder(List<String> activeStageOrder) {
    if (activeStageOrder == null || activeStageOrder.isEmpty()) {
      return;
    }
    this.activeStageOrder = new ArrayList<>(activeStageOrder);
  }

  public List<String> closedStageMarkers() {
    return List.copyOf(closedStageMarkers);
  }

  public void setClosedStageMarkers(List<String> closedStageMarkers) {
    if (closedStageMarkers == null || closedStageMarkers.isEmpty()) {
      return;
    }
    this.closedStageMarkers = new ArrayList<>(closedStageMarkers);
  }

  private static double clampWeight(double value, double fallback) {
    if (Double.isNaN(value) || value < 0.0) {
      return fallback;
    }
    return Math.min(1.0, value);
  }
}
