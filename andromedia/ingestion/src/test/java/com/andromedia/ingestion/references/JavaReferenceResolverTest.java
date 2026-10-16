package com.andromedia.ingestion.references;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.CodeSymbol;
import com.andromedia.common.ProjectSymbolRegistry;
import com.andromedia.common.SymbolType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JavaReferenceResolverTest {

  private ProjectSymbolRegistry registry;
  private JavaReferenceResolver resolver;

  @BeforeEach
  void setUp() {
    registry = new ProjectSymbolRegistry();
    registry.register(
        new CodeSymbol(
            "java:com.foo.AnomalyEvent",
            "AnomalyEvent",
            SymbolType.CLASS,
            "/com/foo/AnomalyEvent.java",
            3,
            8,
            "java",
            null));
    registry.register(
        new CodeSymbol(
            "java:com.foo.AnomalyEvent#getId",
            "getId",
            SymbolType.METHOD,
            "/com/foo/AnomalyEvent.java",
            5,
            7,
            "java",
            "AnomalyEvent"));
    registry.register(
        new CodeSymbol(
            "java:com.bar.Consumer",
            "Consumer",
            SymbolType.CLASS,
            "/com/bar/Consumer.java",
            5,
            11,
            "java",
            null));
    registry.register(
        new CodeSymbol(
            "java:com.bar.Consumer#handle",
            "handle",
            SymbolType.METHOD,
            "/com/bar/Consumer.java",
            8,
            10,
            "java",
            "Consumer"));
    registry.register(
        new CodeSymbol(
            "java:com.other.UserService",
            "UserService",
            SymbolType.CLASS,
            "/com/other/UserService.java",
            1,
            5,
            "java",
            null));
    registry.register(
        new CodeSymbol(
            "java:com.alt.UserService",
            "UserService",
            SymbolType.CLASS,
            "/com/alt/UserService.java",
            1,
            5,
            "java",
            null));
    registry.register(
        new CodeSymbol(
            "java:com.other.UserService#getId",
            "getId",
            SymbolType.METHOD,
            "/com/other/UserService.java",
            3,
            4,
            "java",
            "UserService"));
    resolver = new JavaReferenceResolver();
  }

  @Test
  void resolvesImportedTypeAndMethodReferences() {
    String consumerSource =
        """
        package com.bar;

        import com.foo.AnomalyEvent;

        public class Consumer {
          public void handle(AnomalyEvent event) {
            event.getId();
          }
        }
        """;
    List<String> references =
        resolver.resolveReferences(
            consumerSource,
            1,
            20,
            List.of("com.foo.AnomalyEvent"),
            "com.bar",
            List.of(
                new CodeSymbol(
                    "java:com.bar.Consumer",
                    "Consumer",
                    SymbolType.CLASS,
                    "/Consumer.java",
                    1,
                    20,
                    "java",
                    null),
                new CodeSymbol(
                    "java:com.bar.Consumer#handle",
                    "handle",
                    SymbolType.METHOD,
                    "/Consumer.java",
                    8,
                    10,
                    "java",
                    "Consumer")),
            registry);

    assertTrue(references.contains("java:com.foo.AnomalyEvent"));
    assertTrue(references.contains("java:com.foo.AnomalyEvent#getId"));
    assertFalse(references.contains("java:com.other.UserService#getId"));
    assertFalse(references.contains("java:com.bar.Consumer"));
  }

  @Test
  void resolvesOnlyImportedUserServiceWhenAmbiguous() {
    String source =
        """
        package com.bar;

        import com.other.UserService;

        public class Consumer {
          private UserService service;

          public void run() {
            service.getId();
          }
        }
        """;
    registry.register(
        new CodeSymbol(
            "java:com.other.UserService#getId",
            "getId",
            SymbolType.METHOD,
            "/com/other/UserService.java",
            3,
            4,
            "java",
            "UserService"));

    List<String> references =
        resolver.resolveReferences(
            source,
            1,
            20,
            List.of("com.other.UserService"),
            "com.bar",
            List.of(
                new CodeSymbol(
                    "java:com.bar.Consumer",
                    "Consumer",
                    SymbolType.CLASS,
                    "/Consumer.java",
                    1,
                    20,
                    "java",
                    null)),
            registry);

    assertTrue(references.contains("java:com.other.UserService"));
    assertTrue(references.contains("java:com.other.UserService#getId"));
    assertFalse(references.contains("java:com.alt.UserService"));
  }

  @Test
  void omitsAmbiguousTypeWithoutImport() {
    String source =
        """
        package com.bar;

        public class Consumer {
          public void run(UserService service) {
            service.getId();
          }
        }
        """;
    List<String> references =
        resolver.resolveReferences(
            source,
            1,
            20,
            List.of(),
            "com.bar",
            List.of(
                new CodeSymbol(
                    "java:com.bar.Consumer",
                    "Consumer",
                    SymbolType.CLASS,
                    "/Consumer.java",
                    1,
                    20,
                    "java",
                    null)),
            registry);

    assertFalse(references.contains("java:com.other.UserService"));
    assertFalse(references.contains("java:com.alt.UserService"));
    assertFalse(references.contains("java:com.other.UserService#getId"));
  }

  @Test
  void resolvesSamePackageTypeWithoutImport() {
    registry.register(
        new CodeSymbol(
            "java:com.bar.Helper",
            "Helper",
            SymbolType.CLASS,
            "/com/bar/Helper.java",
            1,
            5,
            "java",
            null));
    registry.register(
        new CodeSymbol(
            "java:com.bar.Helper#run",
            "run",
            SymbolType.METHOD,
            "/com/bar/Helper.java",
            3,
            4,
            "java",
            "Helper"));

    String source =
        """
        package com.bar;

        public class Consumer {
          public void go(Helper helper) {
            helper.run();
          }
        }
        """;
    List<String> references =
        resolver.resolveReferences(
            source,
            1,
            20,
            List.of(),
            "com.bar",
            List.of(
                new CodeSymbol(
                    "java:com.bar.Consumer",
                    "Consumer",
                    SymbolType.CLASS,
                    "/Consumer.java",
                    1,
                    20,
                    "java",
                    null)),
            registry);

    assertTrue(references.contains("java:com.bar.Helper"));
    assertTrue(references.contains("java:com.bar.Helper#run"));
  }

  @Test
  void resolvesStaticImportMethod() {
    registry.register(
        new CodeSymbol(
            "java:com.foo.Util#format",
            "format",
            SymbolType.METHOD,
            "/com/foo/Util.java",
            1,
            3,
            "java",
            "Util"));

    String source =
        """
        package com.bar;

        public class Consumer {
          public void run() {
            format("x");
          }
        }
        """;
    List<String> references =
        resolver.resolveReferences(
            source,
            1,
            20,
            List.of("com.foo.Util.format"),
            "com.bar",
            List.of(
                new CodeSymbol(
                    "java:com.bar.Consumer",
                    "Consumer",
                    SymbolType.CLASS,
                    "/Consumer.java",
                    1,
                    20,
                    "java",
                    null)),
            registry);

    assertTrue(references.contains("java:com.foo.Util#format"));
  }
}
