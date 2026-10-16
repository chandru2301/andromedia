package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.SymbolEntry;
import com.andromedia.common.SymbolType;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImpactAnalysisServiceTest {

  @Test
  void categorizesSymbolsUsingHeuristics() {
    assertEquals(
        ImpactCategory.API,
        ImpactAnalysisService.categorize(
            new SymbolEntry(
                "java:com.foo.UserController#create",
                "create",
                SymbolType.METHOD,
                "src/main/java/com/foo/UserController.java",
                10,
                20,
                "java")));

    assertEquals(
        ImpactCategory.KAFKA_MESSAGING,
        ImpactAnalysisService.categorize(
            new SymbolEntry(
                "java:com.foo.AnomalyKafkaListener#consume",
                "consume",
                SymbolType.METHOD,
                "src/main/java/com/foo/AnomalyKafkaListener.java",
                10,
                20,
                "java")));

    assertEquals(
        ImpactCategory.TEST,
        ImpactAnalysisService.categorize(
            new SymbolEntry(
                "java:com.foo.UserServiceTest#saveUser",
                "saveUser",
                SymbolType.METHOD,
                "src/test/java/com/foo/UserServiceTest.java",
                10,
                20,
                "java")));
  }

  @Test
  void applyCategoryFiltersKeepsOnlyMatchingCategories() {
    List<ImpactSite> sites =
        List.of(
            site("java:com.foo.Listener#onMessage", ImpactCategory.KAFKA_MESSAGING),
            site("java:com.foo.UserController#create", ImpactCategory.API),
            site("java:com.foo.UserService#save", ImpactCategory.SERVICE));

    List<ImpactSite> filtered =
        ImpactAnalysisService.applyCategoryFilters(
            sites, EnumSet.of(ImpactCategory.KAFKA_MESSAGING), 10);

    assertEquals(1, filtered.size());
    assertEquals(ImpactCategory.KAFKA_MESSAGING, filtered.getFirst().category());
  }

  @Test
  void applyCategoryFiltersRespectsMaxResults() {
    List<ImpactSite> sites =
        List.of(
            site("java:com.foo.Listener#one", ImpactCategory.KAFKA_MESSAGING),
            site("java:com.foo.Listener#two", ImpactCategory.KAFKA_MESSAGING));

    List<ImpactSite> filtered =
        ImpactAnalysisService.applyCategoryFilters(
            sites, EnumSet.of(ImpactCategory.KAFKA_MESSAGING), 1);

    assertEquals(1, filtered.size());
  }

  private static ImpactSite site(String symbolId, ImpactCategory category) {
    return ImpactSite.from(
        new SymbolEntry(
            symbolId,
            "method",
            SymbolType.METHOD,
            "src/Foo.java",
            1,
            2,
            "java"),
        1,
        category);
  }

  @Test
  void bfsFindsReferrersWithinDepth() {
    // categorization helper sanity check for repository naming
    assertTrue(
        ImpactAnalysisService.categorize(
                new SymbolEntry(
                    "java:com.foo.UserRepository#findById",
                    "findById",
                    SymbolType.METHOD,
                    "src/main/java/com/foo/UserRepository.java",
                    1,
                    2,
                    "java"))
            == ImpactCategory.REPOSITORY);
  }
}
