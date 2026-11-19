package com.aparttogether.apart_together_be.common;

import java.util.Map;

/** 서비스가 요청을 거절할 때 던진다. 오류 응답(JSON)으로 변환된다. */
public class ApiException extends RuntimeException {

	private final ErrorCode code;
	private final Map<String, Object> details;

	public ApiException(ErrorCode code) {
		this(code, code.defaultMessage(), Map.of());
	}

	public ApiException(ErrorCode code, String message) {
		this(code, message, Map.of());
	}

	public ApiException(ErrorCode code, Map<String, Object> details) {
		this(code, code.defaultMessage(), details);
	}

	public ApiException(ErrorCode code, String message, Map<String, Object> details) {
		super(message);
		this.code = code;
		this.details = details;
	}

	public ErrorCode code() {
		return code;
	}

	public Map<String, Object> details() {
		return details;
	}
}
