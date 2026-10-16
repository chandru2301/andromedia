package com.andromedia.core.investigate;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single live progress event emitted while {@link InvestigationService#investigate} runs.
 * Serialized as SSE {@code data:} JSON by the streaming API endpoint.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InvestigationProgressEvent(
    String type,
    String step,
    String status,
    String detail,
    String sourceType,
    Object result,
    Map<String, String> error) {

  public static InvestigationProgressEvent progress(String step, String status, String detail) {
    return new InvestigationProgressEvent("progress", step, status, detail, null, null, null);
  }

  public static InvestigationProgressEvent progress(
      String step, String status, String detail, String sourceType) {
    return new InvestigationProgressEvent(
        "progress", step, status, detail, sourceType, null, null);
  }

  public static InvestigationProgressEvent result(Object payload) {
    return new InvestigationProgressEvent("result", null, "done", null, null, payload, null);
  }

  public static InvestigationProgressEvent done() {
    return new InvestigationProgressEvent("done", null, "done", null, null, null, null);
  }

  public static InvestigationProgressEvent error(String code, String message) {
    Map<String, String> error = new LinkedHashMap<>();
    error.put("code", code);
    error.put("message", message);
    return new InvestigationProgressEvent("error", null, "done", null, null, null, error);
  }
}
