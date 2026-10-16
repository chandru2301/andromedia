package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.search.ArtifactReference.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Spec for id-aware artifact recall (QA 2026-09-05, B-class): a question that names SOF-4 or the
 * Auth Architecture doc must produce a pinnable, verifiable reference so retrieval cannot drown
 * the artifact under chat noise.
 */
class ArtifactReferenceDetectorTest {

  private static List<String> tokens(List<ArtifactReference> refs, Kind kind) {
    return refs.stream().filter(ref -> ref.kind() == kind).map(ArtifactReference::token).toList();
  }

  // ---------- ticket ids ----------

  @Test
  void detectsTicketIdInTraceQuestion() {
    List<ArtifactReference> refs =
        ArtifactReferenceDetector.detect("what did we decide in SOF-4 about onboarding?");
    assertEquals(List.of("SOF-4"), tokens(refs, Kind.TICKET));
  }

  @Test
  void normalizesLowercaseTicketIds() {
    assertEquals(
        List.of("KAN-12"), tokens(ArtifactReferenceDetector.detect("status of kan-12?"), Kind.TICKET));
  }

  @Test
  void ignoresProseHyphenNumbersAndIsoDates() {
    assertTrue(tokens(ArtifactReferenceDetector.detect("Al Habtoor phase-2 value"), Kind.TICKET).isEmpty());
    assertTrue(tokens(ArtifactReferenceDetector.detect("top-10 deals as of 2026-09-04"), Kind.TICKET).isEmpty());
  }

  // ---------- doc titles ----------

  @Test
  void detectsDocTitleFromAnchorNoun() {
    List<ArtifactReference> refs =
        ArtifactReferenceDetector.detect("where is the auth architecture doc?");
    assertEquals(List.of("auth architecture"), tokens(refs, Kind.DOC));
  }

  @Test
  void detectsQuotedDocTitle() {
    List<ArtifactReference> refs =
        ArtifactReferenceDetector.detect("pull up \"Auth Architecture\" for me");
    assertEquals(List.of("Auth Architecture"), tokens(refs, Kind.DOC));
  }

  @Test
  void skipsSingleGenericWordTitles() {
    assertTrue(tokens(ArtifactReferenceDetector.detect("open the doc"), Kind.DOC).isEmpty());
  }

  @Test
  void detectsBothTicketAndDocInOneQuestion() {
    List<ArtifactReference> refs =
        ArtifactReferenceDetector.detect(
            "what did SOF-4 say about onboarding and where is the auth architecture doc?");
    assertEquals(List.of("SOF-4"), tokens(refs, Kind.TICKET));
    assertEquals(List.of("auth architecture"), tokens(refs, Kind.DOC));
  }

  // ---------- post-retrieval verification ----------

  @Test
  void verifiesTicketOnlyWhenIdIsActuallyPresent() {
    ArtifactReference ref = new ArtifactReference(Kind.TICKET, "SOF-4", "SOF-4");
    assertTrue(ArtifactReferenceDetector.verifies(ref, "SOF-4 Onboarding rework", ""));
    assertTrue(ArtifactReferenceDetector.verifies(ref, "Linear issue", "closed by SOF-4 last week"));
    assertFalse(ArtifactReferenceDetector.verifies(ref, "SOF-41 Billing", "SOF-41 body"));
    assertFalse(ArtifactReferenceDetector.verifies(ref, "onboarding chatter", "we discussed onboarding"));
  }

  @Test
  void verifiesDocTitleAgainstNameOrTitleLineOnly() {
    ArtifactReference ref = new ArtifactReference(Kind.DOC, "auth architecture", "auth architecture");
    assertTrue(ArtifactReferenceDetector.verifies(ref, "Auth Architecture", ""));
    assertTrue(ArtifactReferenceDetector.verifies(ref, "notion-page", "Title: Auth Architecture\nbody"));
    // A page that merely mentions the doc in its body is not the doc.
    assertFalse(
        ArtifactReferenceDetector.verifies(ref, "Standup notes", "see the auth architecture page"));
    assertFalse(ArtifactReferenceDetector.verifies(ref, "Auth Runbook", ""));
  }
}
