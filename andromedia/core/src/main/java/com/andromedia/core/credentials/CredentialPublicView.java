package com.andromedia.core.credentials;

import java.time.Instant;
import java.util.Map;

/** Public (non-secret) view of a stored credential row. */
public record CredentialPublicView(
    String provider,
    boolean hasSecret,
    String status,
    Map<String, Object> metadata,
    Instant updatedAt) {}
