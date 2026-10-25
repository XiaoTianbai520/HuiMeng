package cn.huimeng.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-256-GCM authenticates the provider identifier and key version as additional data. */
@Component
public class SecretBox {
  private final byte[] key;

  public SecretBox(@Value("${huimeng.master-key-file}") String file) throws Exception {
    key = Base64.getDecoder().decode(Files.readString(Path.of(file)).strip());
    if (key.length != 32) throw new IllegalStateException("Master key must be 256 bits");
  }

  public String encrypt(String id, String value) {
    try {
      byte[] nonce = new byte[12];
      new SecureRandom().nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(("v1:" + id).getBytes(StandardCharsets.UTF_8));
      byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder()
          .encodeToString(
              ByteBuffer.allocate(nonce.length + encrypted.length)
                  .put(nonce)
                  .put(encrypted)
                  .array());
    } catch (Exception ex) {
      throw new IllegalStateException("Secret encryption unavailable");
    }
  }

  public String decrypt(String id, String encrypted) {
    try {
      byte[] bytes = Base64.getDecoder().decode(encrypted);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, Arrays.copyOf(bytes, 12)));
      cipher.updateAAD(("v1:" + id).getBytes(StandardCharsets.UTF_8));
      return new String(
          cipher.doFinal(Arrays.copyOfRange(bytes, 12, bytes.length)), StandardCharsets.UTF_8);
    } catch (Exception ex) {
      throw new IllegalStateException("Secret unavailable");
    }
  }
}
