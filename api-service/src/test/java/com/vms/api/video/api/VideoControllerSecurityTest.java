package com.vms.api.video.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vms.api.auth.infrastructure.AuthProperties;
import com.vms.api.common.error.ApiException;
import com.vms.api.common.error.ErrorCode;
import com.vms.api.common.security.CurrentUser;
import com.vms.api.common.security.ProblemSecurityHandlers;
import com.vms.api.common.security.SecurityConfig;
import com.vms.api.video.application.VideoService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = VideoController.class)
@Import({SecurityConfig.class, ProblemSecurityHandlers.class})
@EnableConfigurationProperties(AuthProperties.class)
@TestPropertySource(
    properties = {
      "vms.auth.jwt-secret=c2VjcmV0LXNlY3JldC1zZWNyZXQtc2VjcmV0LXNlY3JldA==",
      "vms.auth.jwt-issuer=vms-api",
      "vms.auth.access-token-ttl=PT15M",
      "vms.auth.refresh-token-ttl=P7D",
      "vms.auth.refresh-rotation-grace=PT10S",
      "vms.auth.cookie-secure=true",
      "vms.auth.cors-allowed-origins=http://localhost:5173",
      "vms.auth.max-failed-logins=5",
      "vms.auth.lock-duration=PT15M"
    })
class VideoControllerSecurityTest {

  @Autowired MockMvc mvc;
  @MockitoBean VideoService videoService;
  @MockitoBean JwtDecoder jwtDecoder;

  private final UUID userId = UUID.randomUUID();

  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(get("/api/v1/videos"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  void otherUsersVideoLooksNotFound() throws Exception {
    UUID videoId = UUID.randomUUID();
    when(videoService.get(eq(new CurrentUser(userId, false)), eq(videoId)))
        .thenThrow(new ApiException(ErrorCode.NOT_FOUND));

    mvc.perform(get("/api/v1/videos/{id}", videoId).with(userJwt()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.trace").doesNotExist());
  }

  @Test
  void playbackBeforeReadyIsConflict() throws Exception {
    UUID videoId = UUID.randomUUID();
    when(videoService.playbackUrl(any(), eq(videoId)))
        .thenThrow(new ApiException(ErrorCode.NOT_READY));

    mvc.perform(get("/api/v1/videos/{id}/playback-url", videoId).with(userJwt()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("NOT_READY"));
  }

  @Test
  void invalidTrackingIdIsRejectedWithFieldErrors() throws Exception {
    mvc.perform(
            post("/api/v1/videos")
                .with(userJwt())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"trackingId\":\"bad id!\",\"contentType\":\"video/webm\",\"sizeBytes\":10}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("trackingId"));
  }

  @Test
  void adminEndpointForbiddenForUsers() throws Exception {
    mvc.perform(post("/api/v1/admin/videos/{id}/reprocess", UUID.randomUUID()).with(userJwt()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor userJwt() {
    return jwt()
        .jwt(j -> j.subject(userId.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }
}
