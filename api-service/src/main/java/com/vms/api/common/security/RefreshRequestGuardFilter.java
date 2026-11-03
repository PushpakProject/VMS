package com.vms.api.common.security;

import com.vms.api.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF defence for the only cookie-authenticated endpoints ({@code /auth/refresh}, {@code
 * /auth/logout}). Besides SameSite=Strict, require a custom header (which forces a CORS preflight
 * for cross-site callers) and, when present, an allowed Origin.
 */
public class RefreshRequestGuardFilter extends OncePerRequestFilter {

  static final String REQUIRED_HEADER = "X-Requested-With";
  private static final Set<String> GUARDED_PATHS =
      Set.of("/api/v1/auth/refresh", "/api/v1/auth/logout");

  private final List<String> allowedOrigins;
  private final ProblemSecurityHandlers problems;

  public RefreshRequestGuardFilter(List<String> allowedOrigins, ProblemSecurityHandlers problems) {
    this.allowedOrigins = allowedOrigins;
    this.problems = problems;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod()) || !GUARDED_PATHS.contains(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String origin = request.getHeader("Origin");
    boolean headerPresent = "XMLHttpRequest".equals(request.getHeader(REQUIRED_HEADER));
    boolean originAllowed = origin == null || allowedOrigins.contains(origin);
    if (!headerPresent || !originAllowed) {
      problems.write(response, ErrorCode.FORBIDDEN);
      return;
    }
    chain.doFilter(request, response);
  }
}
