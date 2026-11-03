package com.vms.api.auth.infrastructure;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("vms.auth")
public record AuthProperties(
    @NotBlank String jwtSecret,
    @NotBlank String jwtIssuer,
    @NotNull Duration accessTokenTtl,
    @NotNull Duration refreshTokenTtl,
    @NotNull Duration refreshRotationGrace,
    boolean cookieSecure,
    @NotNull List<String> corsAllowedOrigins,
    @Min(1) int maxFailedLogins,
    @NotNull Duration lockDuration) {}
