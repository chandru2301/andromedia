package com.andromedia.core.trial;

/**
 * Stable trial/service id derived from a Clerk {@code sub} claim ({@code user_…}).
 * Matches the landing {@code trialIdFromClerkUserId} helper.
 */
public final class TrialIdMapper {

  private TrialIdMapper() {}

  public static String fromClerkUserId(String clerkUserId) {
    if (clerkUserId == null || clerkUserId.isBlank()) {
      throw new IllegalArgumentException("Clerk user id is required");
    }
    String cleaned = clerkUserId.trim().replaceAll("[^a-zA-Z0-9_-]", "");
    if (cleaned.isBlank()) {
      throw new IllegalArgumentException("Invalid Clerk user id");
    }
    return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
  }

  public static boolean matches(String trialId, String clerkUserId) {
    if (trialId == null || clerkUserId == null) {
      return false;
    }
    return trialId.trim().equals(fromClerkUserId(clerkUserId));
  }
}
