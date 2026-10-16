package com.andromedia.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SideQueryServiceTest {

  private final SideQueryService service = new SideQueryService(null, null, new SideQueryProperties());

  @Test
  void parsesJsonIntentBlock() {
    String raw =
        """
        ```json
        {"intent":"IMPACT","symbol":"AnomalyEvent","direction":"reverse","depth":3,"explain":true}
        ```
        """;
    var parsed = service.parseResponse(raw);
    assertTrue(parsed.isPresent());
    assertEquals(SideQueryService.SideQueryIntentType.IMPACT, parsed.get().intent());
    assertEquals("AnomalyEvent", parsed.get().symbol());
    assertEquals("reverse", parsed.get().direction());
    assertEquals(3, parsed.get().depth());
    assertTrue(parsed.get().explain());
  }

  @Test
  void parsesBareJsonObject() {
    var parsed =
        service.parseResponse(
            "{\"intent\":\"PLAN\",\"planQuery\":\"add retry to listener\",\"graphContext\":false}");
    assertTrue(parsed.isPresent());
    assertEquals(SideQueryService.SideQueryIntentType.PLAN, parsed.get().intent());
    assertEquals("add retry to listener", parsed.get().planQuery());
  }
}
