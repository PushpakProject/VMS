package com.vms.api.common.security;

import com.vms.api.common.error.ErrorCode;
import com.vms.api.common.error.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes 401/403 from the security filter chain as RFC 7807 JSON, same as controller errors. */
@Component
public class ProblemSecurityHandlers {

  private final JsonMapper jsonMapper;

  public ProblemSecurityHandlers(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  public AuthenticationEntryPoint entryPoint() {
    return (request, response, ex) -> write(response, ErrorCode.UNAUTHENTICATED);
  }

  public AccessDeniedHandler accessDeniedHandler() {
    return (request, response, ex) -> write(response, ErrorCode.FORBIDDEN);
  }

  public void write(HttpServletResponse response, ErrorCode code) throws IOException {
    response.setStatus(code.status().value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    jsonMapper.writeValue(
        response.getOutputStream(), GlobalExceptionHandler.toProblem(code, code.defaultMessage()));
  }
}
