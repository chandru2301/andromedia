package com.andromedia.core.investigate;

/** Receives live {@link InvestigationProgressEvent}s emitted during {@code investigate()}. */
public interface InvestigationProgressSink {

  InvestigationProgressSink NOOP = event -> {};

  void emit(InvestigationProgressEvent event);
}
