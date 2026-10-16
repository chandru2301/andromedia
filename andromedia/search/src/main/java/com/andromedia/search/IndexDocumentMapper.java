package com.andromedia.search;

import com.andromedia.common.ChunkUnitType;
import com.andromedia.common.IndexConstants;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.apache.lucene.document.Document;

final class IndexDocumentMapper {

  private IndexDocumentMapper() {}

  static SearchHit toSearchHit(Document document, float score) {
    String unitTypeValue = document.get(IndexConstants.FIELD_UNIT_TYPE);
    ChunkUnitType unitType =
        unitTypeValue == null ? ChunkUnitType.FILE : ChunkUnitType.valueOf(unitTypeValue);
    Optional<ZohoHitMetadata> zoho = readZohoMetadata(document);
    return new SearchHit(
        document.get(IndexConstants.FIELD_PATH),
        document.get(IndexConstants.FIELD_FILE_NAME),
        score,
        document.get(IndexConstants.FIELD_CHUNK_ID),
        document.get(IndexConstants.FIELD_CLASS_NAME),
        document.get(IndexConstants.FIELD_METHOD_NAME),
        unitType,
        parseLine(document.get(IndexConstants.FIELD_START_LINE)),
        parseLine(document.get(IndexConstants.FIELD_END_LINE)),
        null,
        null,
        readMultiValue(document, IndexConstants.FIELD_IMPORTS),
        readMultiValue(document, IndexConstants.FIELD_SYMBOL_IDS),
        readMultiValue(document, IndexConstants.FIELD_SYMBOL_NAMES),
        optionalField(document, IndexConstants.FIELD_SOURCE_TYPE),
        optionalField(document, IndexConstants.FIELD_COMMIT_SHA),
        optionalField(document, IndexConstants.FIELD_TIMESTAMP),
        optionalField(document, IndexConstants.FIELD_CONTENT),
        zoho);
  }

  private static Optional<ZohoHitMetadata> readZohoMetadata(Document document) {
    Optional<String> sourceType = optionalField(document, IndexConstants.FIELD_SOURCE_TYPE);
    if (sourceType.isEmpty() || !IndexConstants.SOURCE_TYPE_ZOHO.equals(sourceType.get())) {
      return Optional.empty();
    }
    ZohoHitMetadata meta =
        new ZohoHitMetadata(
            optionalField(document, IndexConstants.FIELD_DEAL_STAGE),
            optionalField(document, IndexConstants.FIELD_DEAL_AMOUNT),
            optionalField(document, IndexConstants.FIELD_EXPECTED_REVENUE),
            optionalField(document, IndexConstants.FIELD_PROBABILITY),
            optionalField(document, IndexConstants.FIELD_CLOSING_DATE),
            optionalField(document, IndexConstants.FIELD_DEAL_OWNER),
            optionalField(document, IndexConstants.FIELD_LEAD_SOURCE),
            optionalField(document, IndexConstants.FIELD_NEXT_STEP),
            optionalField(document, IndexConstants.FIELD_ACCOUNT_NAME),
            optionalField(document, IndexConstants.FIELD_ACCOUNT_DESCRIPTION),
            optionalField(document, IndexConstants.FIELD_QUALITY_SCORE),
            readMultiValue(document, IndexConstants.FIELD_QUALITY_SIGNALS),
            readMultiValue(document, IndexConstants.FIELD_ZOHO_CONTACTS),
            readMultiValue(document, IndexConstants.FIELD_ZOHO_NOTES));
    return Optional.of(meta);
  }

  private static java.util.Optional<String> optionalField(
      Document document, String field) {
    String value = document.get(field);
    return value == null || value.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(value);
  }

  static UsageSite toUsageSite(Document document, UsageRole role, String matchedSymbolId) {
    String unitTypeValue = document.get(IndexConstants.FIELD_UNIT_TYPE);
    ChunkUnitType unitType =
        unitTypeValue == null ? ChunkUnitType.FILE : ChunkUnitType.valueOf(unitTypeValue);
    String symbolName = pickSymbolName(document, matchedSymbolId);
    return new UsageSite(
        matchedSymbolId,
        symbolName,
        document.get(IndexConstants.FIELD_PATH),
        document.get(IndexConstants.FIELD_FILE_NAME),
        parseLine(document.get(IndexConstants.FIELD_START_LINE)),
        parseLine(document.get(IndexConstants.FIELD_END_LINE)),
        unitType,
        document.get(IndexConstants.FIELD_CHUNK_ID),
        role);
  }

  private static String pickSymbolName(Document document, String matchedSymbolId) {
    List<String> symbolIds = readMultiValue(document, IndexConstants.FIELD_SYMBOL_IDS);
    List<String> symbolNames = readMultiValue(document, IndexConstants.FIELD_SYMBOL_NAMES);
    for (int i = 0; i < symbolIds.size(); i++) {
      if (matchedSymbolId.equals(symbolIds.get(i)) && i < symbolNames.size()) {
        return symbolNames.get(i);
      }
    }
    if (!symbolNames.isEmpty()) {
      return symbolNames.getFirst();
    }
    int hash = matchedSymbolId.indexOf('#');
    if (hash >= 0) {
      return matchedSymbolId.substring(hash + 1);
    }
    int dot = matchedSymbolId.lastIndexOf('.');
    if (dot >= 0) {
      return matchedSymbolId.substring(dot + 1);
    }
    return matchedSymbolId;
  }

  private static List<String> readMultiValue(Document document, String field) {
    String[] values = document.getValues(field);
    if (values == null || values.length == 0) {
      return List.of();
    }
    return Arrays.asList(values);
  }

  private static int parseLine(String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ex) {
      return 0;
    }
  }
}
