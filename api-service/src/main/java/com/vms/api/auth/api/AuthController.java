package com.vms.api.auth.api;

import com.vms.api.auth.api.AuthDtos.AuthResponse;
import com.vms.api.auth.api.AuthDtos.GoogleLoginRequest;
import com.vms.api.auth.api.AuthDtos.LoginRequest;
import com.vms.api.auth.api.AuthDtos.RegisterRequest;
import com.vms.api.auth.api.AuthDtos.UserResponse;
import com.vms.api.auth.application.AuthService;
import com.vms.api.auth.application.AuthService.AuthResult;
import com.vms.api.auth.application.strategy.Credentials;
import com.vms.api.common.error.ApiException;
import com.vms.api.common.error.ErrorCode;
import com.vms.api.common.error.GlobalExceptionHandler;
import com.vms.api.common.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final AuthService authService;
  private final RefreshCookies cookies;

  public AuthController(AuthService authService, RefreshCookies cookies) {
    this.authService = authService;
    this.cookies = cookies;
  }

  @PostMapping("/login")
  public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
    return respond(
        HttpStatus.OK,
        authService.login(new Credentials.Password(request.login(), request.password())));
  }

  @PostMapping("/google")
  public ResponseEntity<AuthResponse> google(@Valid @RequestBody GoogleLoginRequest request) {
    return respond(
        HttpStatus.OK, authService.login(new Credentials.GoogleIdToken(request.idToken())));
  }

  @PostMapping("/register")
  public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
    return respond(
        HttpStatus.CREATED,
        authService.register(
            request.email(), request.username(), request.displayName(), request.password()));
  }

  @PostMapping("/refresh")
  public ResponseEntity<?> refresh(
      @CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken) {
    try {
      if (refreshToken == null || refreshToken.isBlank()) {
        throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
      }
      return respond(HttpStatus.OK, authService.refresh(refreshToken));
    } catch (ApiException ex) {
      return ResponseEntity.status(ex.code().status())
          .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
          .body(GlobalExceptionHandler.toProblem(ex.code(), ex.getMessage()));
    }
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken) {
    if (refreshToken != null && !refreshToken.isBlank()) {
      authService.logout(refreshToken);
    }
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
        .build();
  }

  @GetMapping("/me")
  public UserResponse me(CurrentUser currentUser) {
    return UserResponse.from(authService.currentUser(currentUser.id()));
  }

  private ResponseEntity<AuthResponse> respond(HttpStatus status, AuthResult result) {
    return ResponseEntity.status(status)
        .header(
            HttpHeaders.SET_COOKIE,
            cookies
                .create(result.refreshToken().value(), result.refreshToken().maxAgeSeconds())
                .toString())
        .body(
            new AuthResponse(
                result.accessToken().value(),
                result.accessToken().expiresInSeconds(),
                UserResponse.from(result.user())));
  }
}
