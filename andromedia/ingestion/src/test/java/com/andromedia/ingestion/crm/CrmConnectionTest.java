package com.andromedia.ingestion.crm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CrmConnectionTest {

  @Test
  void acceptsZohoAndNormalizesCase() {
    CrmConnection lower = new CrmConnection("org-1", "zoho", Map.of());
    assertEquals(CrmConnection.PROVIDER_ZOHO, lower.provider());

    CrmConnection mixed = new CrmConnection("org-1", "Zoho", Map.of());
    assertEquals(CrmConnection.PROVIDER_ZOHO, mixed.provider());
  }

  @Test
  void rejectsSalesforceWithClearMessage() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> new CrmConnection("org-1", "salesforce", Map.of()));
    assertEquals(CrmConnection.UNSUPPORTED_PROVIDER_MESSAGE, ex.getMessage());
  }

  @Test
  void rejectsHubSpotWithClearMessage() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> new CrmConnection("org-1", "hubspot", Map.of()));
    assertEquals(CrmConnection.UNSUPPORTED_PROVIDER_MESSAGE, ex.getMessage());
  }

  @Test
  void rejectsBlankAndNullProvider() {
    IllegalArgumentException blank =
        assertThrows(
            IllegalArgumentException.class, () -> new CrmConnection("org-1", "", Map.of()));
    assertEquals(CrmConnection.UNSUPPORTED_PROVIDER_MESSAGE, blank.getMessage());

    IllegalArgumentException nullProvider =
        assertThrows(
            IllegalArgumentException.class, () -> new CrmConnection("org-1", null, Map.of()));
    assertEquals(CrmConnection.UNSUPPORTED_PROVIDER_MESSAGE, nullProvider.getMessage());
  }
}
