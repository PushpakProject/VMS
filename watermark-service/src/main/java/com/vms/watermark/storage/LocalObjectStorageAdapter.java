package com.vms.watermark.storage;

import com.vms.watermark.exception.RetryableProcessingException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Local-development storage on a volume shared with the API's emulator. Uses the same {@code
 * .content-type} sidecar convention, plus a {@code .metadata} properties sidecar.
 */
public class LocalObjectStorageAdapter implements ObjectStoragePort {

  private static final String CONTENT_TYPE_SUFFIX = ".content-type";
  private static final String METADATA_SUFFIX = ".metadata";

  private final Path root;

  public LocalObjectStorageAdapter(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  @Override
  public Optional<StoredObject> stat(String key) {
    Path file = resolve(key);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      Path typeFile = sidecar(file, CONTENT_TYPE_SUFFIX);
      String type =
          Files.exists(typeFile) ? Files.readString(typeFile) : "application/octet-stream";
      return Optional.of(new StoredObject(Files.size(file), type, readMetadata(file)));
    } catch (IOException ex) {
      throw new RetryableProcessingException("Local stat failed", ex);
    }
  }

  @Override
  public void download(String key, Path target) {
    try {
      Files.copy(resolve(key), target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException ex) {
      throw new RetryableProcessingException("Local download failed", ex);
    }
  }

  @Override
  public boolean createIfAbsent(
      String key, Path source, String contentType, Map<String, String> metadata) {
    Path target = resolve(key);
    try {
      Files.createDirectories(target.getParent());
      Path temp = Files.createTempFile(target.getParent(), ".create-", ".tmp");
      try {
        Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(temp, target);
        Files.writeString(sidecar(target, CONTENT_TYPE_SUFFIX), contentType);
        writeMetadata(target, metadata);
        return true;
      } catch (FileAlreadyExistsException ex) {
        return false;
      } finally {
        Files.deleteIfExists(temp);
      }
    } catch (IOException ex) {
      throw new RetryableProcessingException("Local upload failed", ex);
    }
  }

  @Override
  public void delete(String key) {
    Path file = resolve(key);
    try {
      Files.deleteIfExists(file);
      Files.deleteIfExists(sidecar(file, CONTENT_TYPE_SUFFIX));
      Files.deleteIfExists(sidecar(file, METADATA_SUFFIX));
    } catch (IOException ex) {
      throw new RetryableProcessingException("Local delete failed", ex);
    }
  }

  private Path resolve(String key) {
    Path file = root.resolve(key).normalize();
    if (!file.startsWith(root) || file.equals(root)) {
      throw new IllegalArgumentException("Invalid object key");
    }
    return file;
  }

  private static Path sidecar(Path file, String suffix) {
    return file.resolveSibling(file.getFileName() + suffix);
  }

  private static Map<String, String> readMetadata(Path file) throws IOException {
    Path metaFile = sidecar(file, METADATA_SUFFIX);
    Map<String, String> result = new HashMap<>();
    if (Files.exists(metaFile)) {
      Properties props = new Properties();
      try (InputStream in = Files.newInputStream(metaFile)) {
        props.load(in);
      }
      props.forEach((k, v) -> result.put(k.toString(), v.toString()));
    }
    return result;
  }

  private static void writeMetadata(Path file, Map<String, String> metadata) throws IOException {
    Properties props = new Properties();
    props.putAll(metadata);
    try (OutputStream out = Files.newOutputStream(sidecar(file, METADATA_SUFFIX))) {
      props.store(out, null);
    }
  }
}
