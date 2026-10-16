package com.andromedia.core.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Base64;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialCipherAndVaultTest {

  @TempDir Path tempDir;

  @Test
  void cipherRoundTripsPlaintext() throws Exception {
    CredentialCipher cipher = new CredentialCipher(randomKey());
    String encrypted = cipher.encrypt("refresh-secret-value");
    assertNotEquals("refresh-secret-value", encrypted);
    assertEquals("refresh-secret-value", cipher.decrypt(encrypted));
  }

  @Test
  void vaultUpsertsAndDecryptsWithoutPlaintextOnDisk() throws Exception {
    CredentialCipher cipher = new CredentialCipher(randomKey());
    ZohoConnectionVault vault = new ZohoConnectionVault(cipher, tempDir);

    ZohoConnectionRecord saved =
        vault.upsertZohoConnection(
            "local", "client-1", "secret-1", "refresh-1", "in");

    assertFalse(saved.id().isBlank());
    assertFalse(saved.connectionKey().isBlank());
    assertTrue(saved.active());

    String onDisk = java.nio.file.Files.readString(tempDir.resolve("local.json"));
    assertFalse(onDisk.contains("refresh-1"));
    assertFalse(onDisk.contains("secret-1"));
    assertTrue(onDisk.contains("refreshTokenEnc"));

    ZohoConnectionVault.DecryptedZohoCredentials creds =
        vault.requireActiveCredentials("local", saved.id());
    assertEquals("client-1", creds.clientId());
    assertEquals("secret-1", creds.clientSecret());
    assertEquals("refresh-1", creds.refreshToken());
    assertEquals("in", creds.region());
  }

  @Test
  void revokedConnectionRequiresReauth() throws Exception {
    CredentialCipher cipher = new CredentialCipher(randomKey());
    ZohoConnectionVault vault = new ZohoConnectionVault(cipher, tempDir);
    ZohoConnectionRecord saved =
        vault.upsertZohoConnection("svc", "c", "s", "r", "com");
    vault.markRevoked("svc", saved.id());

    ZohoReauthRequiredException ex =
        assertThrows(
            ZohoReauthRequiredException.class,
            () -> vault.requireActiveCredentials("svc", saved.id()));
    assertEquals(ZohoReauthRequiredException.CODE, ex.code());
    assertEquals(saved.id(), ex.connectionId());
  }

  @Test
  void missingEncryptionKeyFailsClosedUnlessEphemeralAllowed() {
    assertThrows(
        IllegalStateException.class,
        () -> new CredentialCipher(new CredentialsProperties("", false, false)));
    CredentialCipher ephemeral =
        new CredentialCipher(new CredentialsProperties("", true, false));
    assertEquals("x", ephemeral.decrypt(ephemeral.encrypt("x")));
  }

  private static SecretKey randomKey() throws Exception {
    KeyGenerator generator = KeyGenerator.getInstance("AES");
    generator.init(256);
    return generator.generateKey();
  }
}
