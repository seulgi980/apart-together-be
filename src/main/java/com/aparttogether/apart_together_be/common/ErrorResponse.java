package com.aparttogether.apart_together_be.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** 오류 응답 본문: { code, message, ...추가 정보 } */
public final class ErrorResponse {

	private ErrorResponse() {
	}

	public static Map<String, Object> of(ErrorCode code, String message, Map<String, Object> details) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("code", code.name());
		body.put("message", message);
		body.putAll(details);
		return body;
	}

	public static Map<String, Object> of(ErrorCode code) {
		return of(code, code.defaultMessage(), Map.of());
	}
}
