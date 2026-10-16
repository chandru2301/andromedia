package com.andromedia.core.credentials;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM helper for credential vault fields. Ciphertext format: {@code base64(iv ||
 * ciphertext+tag)}.
 */
@Component
public class CredentialCipher {

  private static final Logger log = LoggerFactory.getLogger(CredentialCipher.class);
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int GCM_TAG_BITS = 128;
  private static final int IV_BYTES = 12;
  private static final int KEY_BYTES = 32;

  private final SecretKey secretKey;
  private final SecureRandom secureRandom = new SecureRandom();

  @Autowired
  public CredentialCipher(CredentialsProperties properties) {
    this.secretKey = resolveKey(properties);
  }

  /** Test/helper constructor. */
  CredentialCipher(SecretKey secretKey) {
    this.secretKey = secretKey;
  }

  public String encrypt(String plaintext) {
    if (plaintext == null) {
      plaintext = "";
    }
    try {
      byte[] iv = new byte[IV_BYTES];
      secureRandom.nextBytes(iv);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
      byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherBytes.length);
      buffer.put(iv);
      buffer.put(cipherBytes);
      return Base64.getEncoder().encodeToString(buffer.array());
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to encrypt credential field", ex);
    }
  }

  public String decrypt(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      return "";
    }
    try {
      byte[] raw = Base64.getDecoder().decode(encoded.trim());
      if (raw.length <= IV_BYTES) {
        throw new IllegalArgumentException("Ciphertext too short");
      }
      byte[] iv = Arrays.copyOfRange(raw, 0, IV_BYTES);
      byte[] cipherBytes = Arrays.copyOfRange(raw, IV_BYTES, raw.length);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
      byte[] plain = cipher.doFinal(cipherBytes);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to decrypt credential field", ex);
    }
  }

  private static SecretKey resolveKey(CredentialsProperties properties) {
    String configured = properties == null ? "" : properties.encryptionKey();
    if (configured != null && !configured.isBlank()) {
      byte[] decoded;
      try {
        decoded = Base64.getDecoder().decode(configured.trim());
      } catch (IllegalArgumentException ex) {
        throw new IllegalStateException(
            "andromedia.credentials.encryption-key must be base64-encoded", ex);
      }
      if (decoded.length != KEY_BYTES) {
        throw new IllegalStateException(
            "andromedia.credentials.encryption-key must decode to 32 bytes (AES-256)");
      }
      return new SecretKeySpec(decoded, "AES");
    }
    boolean allowEphemeral = properties != null && properties.allowEphemeralKey();
    if (!allowEphemeral) {
      throw new IllegalStateException(
          "andromedia.credentials.encryption-key is required (set ANDROMEDIA_CREDENTIALS_ENCRYPTION_KEY). "
              + "For local/dev only, set andromedia.credentials.allow-ephemeral-key=true");
    }
    try {
      KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      SecretKey ephemeral = generator.generateKey();
      log.warn(
          "Using ephemeral AES credential key — Zoho vault entries will not survive process restart. "
              + "Set ANDROMEDIA_CREDENTIALS_ENCRYPTION_KEY for durable encryption.");
      return ephemeral;
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to generate ephemeral credential key", ex);
    }
  }
}
