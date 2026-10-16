package com.andromedia.cli.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.llm.ContextBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextNarratorTest {

  @Test
  void limitsAndDedupesFileNames() {
    List<ContextBlock> blocks =
        List.of(
            block("a", "Foo.java"),
            block("b", "Foo.java"),
            block("c", "Bar.java"),
            block("d", "Baz.java"),
            block("e", "Qux.java"),
            block("f", "Waldo.java"),
            block("g", "Extra.java"));

    List<String> files = ContextNarrator.uniqueFileNames(blocks);
    assertEquals(5, files.size());
    assertEquals("Foo.java", files.getFirst());
    assertTrue(files.contains("Waldo.java"));
    assertFalse(files.contains("Extra.java"));
  }

  private static ContextBlock block(String id, String fileName) {
    return new ContextBlock(id, "/p/" + fileName, fileName, "code", fileName, "C", "m", 1, 2);
  }
}
