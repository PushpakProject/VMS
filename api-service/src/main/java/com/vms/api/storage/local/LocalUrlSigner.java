package com.vms.api.storage.local;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 signatures for the local storage emulator (mirrors what GCS does with V4). */
public class LocalUrlSigner {

  private final byte[] key;

  public LocalUrlSigner(String signingKey) {
    if (signingKey == null || signingKey.length() < 32) {
      throw new IllegalStateException("vms.storage.local-signing-key must be at least 32 chars");
    }
    this.key = signingKey.getBytes(StandardCharsets.UTF_8);
  }

  public String sign(
      String method, String objectKey, long expiresEpochSec, String contentType, long maxBytes) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      String payload =
          String.join(
              "\n",
              method,
              objectKey,
              Long.toString(expiresEpochSec),
              contentType,
              Long.toString(maxBytes));
      return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public boolean verify(
      String signature,
      String method,
      String objectKey,
      long expiresEpochSec,
      String contentType,
      long maxBytes) {
    if (signature == null) {
      return false;
    }
    byte[] expected =
        sign(method, objectKey, expiresEpochSec, contentType, maxBytes)
            .getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8));
  }
}
