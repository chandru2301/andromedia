package com.andromedia.ingestion.references;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.CodeSymbol;
import com.andromedia.common.ProjectSymbolRegistry;
import com.andromedia.common.SymbolType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PythonReferenceResolverTest {

  private ProjectSymbolRegistry registry;
  private PythonReferenceResolver resolver;

  @BeforeEach
  void setUp() {
    registry = new ProjectSymbolRegistry();
    registry.register(
        new CodeSymbol(
            "python:provider.create_event",
            "create_event",
            SymbolType.FUNCTION,
            "/provider.py",
            5,
            6,
            "python",
            "provider"));
    registry.register(
        new CodeSymbol(
            "python:provider.AnomalyEvent",
            "AnomalyEvent",
            SymbolType.CLASS,
            "/provider.py",
            1,
            3,
            "python",
            "provider"));
    resolver = new PythonReferenceResolver();
  }

  @Test
  void resolvesImportedFunctionReferences() {
    String consumerSource =
        """
        from provider import create_event, AnomalyEvent

        def consume(event_id):
            event = create_event(event_id)
            return event.event_id
        """;
    List<String> references =
        resolver.resolveReferences(
            consumerSource,
            1,
            10,
            List.of("from provider import create_event, AnomalyEvent"),
            "consumer",
            List.of(
                new CodeSymbol(
                    "python:consumer.consume",
                    "consume",
                    SymbolType.FUNCTION,
                    "/consumer.py",
                    3,
                    5,
                    "python",
                    "consumer")),
            registry);

    assertTrue(references.contains("python:provider.create_event"));
    assertTrue(references.contains("python:provider.AnomalyEvent"));
  }
}
