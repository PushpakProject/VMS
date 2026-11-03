package com.vms.api.auth.api;

import com.vms.api.auth.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Request/response contracts for the auth endpoints. */
public final class AuthDtos {

  private AuthDtos() {}

  public record LoginRequest(
      @NotBlank @Size(max = 254) String login, @NotBlank @Size(max = 128) String password) {}

  public record RegisterRequest(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{3,50}$") String username,
      @NotBlank @Size(max = 100) String displayName,
      @NotBlank @Size(min = 12, max = 128) String password) {}

  public record GoogleLoginRequest(@NotBlank @Size(max = 4096) String idToken) {}

  public record UserResponse(UUID id, String email, String displayName, String role) {

    public static UserResponse from(User user) {
      return new UserResponse(
          user.getId(), user.getEmail(), user.getDisplayName(), user.getRole().name());
    }
  }

  public record AuthResponse(String accessToken, long expiresIn, UserResponse user) {}
}
