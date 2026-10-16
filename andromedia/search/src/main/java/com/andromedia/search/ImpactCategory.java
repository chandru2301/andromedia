package com.andromedia.search;

public enum ImpactCategory {
  API("APIs"),
  KAFKA_MESSAGING("Kafka / messaging"),
  REPOSITORY("Repositories / DB"),
  TEST("Tests"),
  SERVICE("Services / consumers"),
  OTHER("Other");

  private final String label;

  ImpactCategory(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
