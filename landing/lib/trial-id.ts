/**
 * Clerk user ids look like `user_2abc…`. Trial/service ids must stay alphanumeric + hyphen/underscore
 * for backend binding and Lucene service path segments.
 */
export function trialIdFromClerkUserId(userId: string): string {
  const cleaned = userId.trim().replace(/[^a-zA-Z0-9_-]/g, "");
  if (!cleaned) {
    throw new Error("Invalid Clerk user id for trial");
  }
  return cleaned.length > 64 ? cleaned.slice(0, 64) : cleaned;
}
