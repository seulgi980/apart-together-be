package com.aparttogether.apart_together_be.common;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
		return ResponseEntity.status(e.code().status())
				.body(ErrorResponse.of(e.code(), e.getMessage(), e.details()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
		Map<String, String> fields = new LinkedHashMap<>();
		e.getBindingResult().getFieldErrors()
				.forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
		String message = fields.values().stream().findFirst().orElse(ErrorCode.VALIDATION_FAILED.defaultMessage());
		return ResponseEntity.badRequest()
				.body(ErrorResponse.of(ErrorCode.VALIDATION_FAILED, message, Map.of("fields", fields)));
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
			MissingServletRequestParameterException.class })
	public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e) {
		return ResponseEntity.badRequest().body(ErrorResponse.of(ErrorCode.VALIDATION_FAILED));
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<Map<String, Object>> handleUploadSize(MaxUploadSizeExceededException e) {
		return ResponseEntity.badRequest()
				.body(ErrorResponse.of(ErrorCode.INVALID_IMAGE, "사진은 2MB 이하만 올릴 수 있습니다.", Map.of()));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
		return ResponseEntity.status(404).body(ErrorResponse.of(ErrorCode.NOT_FOUND));
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<Map<String, Object>> handleMethod(HttpRequestMethodNotSupportedException e) {
		return ResponseEntity.status(405)
				.body(ErrorResponse.of(ErrorCode.VALIDATION_FAILED, "지원하지 않는 요청 방식입니다.", Map.of()));
	}

	/** 유니크/CHECK 위반 중 서비스가 따로 변환하지 못한 것, 잠금 대기 실패 */
	@ExceptionHandler({ DataIntegrityViolationException.class, PessimisticLockingFailureException.class })
	public ResponseEntity<Map<String, Object>> handleConflict(Exception e) {
		log.warn("DB 충돌: {}", e.getMessage());
		return ResponseEntity.status(409).body(ErrorResponse.of(ErrorCode.CONFLICT));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
		log.error("처리하지 못한 오류", e);
		return ResponseEntity.internalServerError().body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
	}
}
