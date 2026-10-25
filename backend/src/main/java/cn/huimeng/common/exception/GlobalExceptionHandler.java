package cn.huimeng.common.exception;

import cn.huimeng.dto.resp.ErrorRespDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一转换业务与参数异常；响应不携带 SQL、原始路径、输入或堆栈。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final ObjectMapper JSON = new ObjectMapper();

  public static ErrorRespDTO body(HttpServletRequest request, String code, String message) {
    Object requestId = request.getAttribute("requestId");
    return new ErrorRespDTO(code, message, requestId instanceof String id ? id : null, null);
  }

  public static void write(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response.setHeader("Cache-Control", "no-store");
    JSON.writeValue(response.getOutputStream(), body(request, code, message));
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ErrorRespDTO> known(ApiException ex, HttpServletRequest request) {
    return ResponseEntity.status(ex.status).body(body(request, ex.code, ex.getMessage()));
  }

  @ExceptionHandler({
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.bind.MissingRequestHeaderException.class,
    org.springframework.dao.DataIntegrityViolationException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<ErrorRespDTO> invalid(Exception ex, HttpServletRequest request) {
    return ResponseEntity.badRequest().body(body(request, "INVALID_ARGUMENT", "请求参数无效"));
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ErrorRespDTO> unsupportedMedia(Exception ex, HttpServletRequest request) {
    return ResponseEntity.status(415).body(body(request, "UNSUPPORTED_MEDIA_TYPE", "请求媒体类型不受支持"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorRespDTO> unexpected(Exception ex, HttpServletRequest request) {
    return ResponseEntity.status(503).body(body(request, "EXECUTOR_UNAVAILABLE", "服务暂不可用"));
  }
}
