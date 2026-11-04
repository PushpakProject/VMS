package com.vms.api.storage.gcs;

import com.vms.api.common.error.ApiException;
import com.vms.api.common.error.ErrorCode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Starts a GCS resumable session by POSTing to a V4 signed URL and returns the session URI from the
 * {@code Location} header. Separated out so the adapter can be tested without network.
 */
@FunctionalInterface
public interface ResumableSessionStarter {

  String start(URI signedInitUrl, Map<String, String> headers);

  static ResumableSessionStarter http() {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    return (url, headers) -> {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(url)
              .timeout(Duration.ofSeconds(10))
              .POST(HttpRequest.BodyPublishers.noBody());
      headers.forEach(request::header);
      try {
        HttpResponse<Void> response =
            client.send(request.build(), HttpResponse.BodyHandlers.discarding());
        String location = response.headers().firstValue("Location").orElse(null);
        if (response.statusCode() != 201 || location == null) {
          throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
        }
        return location;
      } catch (IOException ex) {
        throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
      }
    };
  }
}
