package com.aparttogether.apart_together_be.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 코드에 박지 않는 설정값 (application.yaml의 app.*) */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
		String publicBaseUrl,
		Cors cors,
		Jwt jwt,
		Auth auth,
		Trip trip,
		Schedule schedule,
		Chat chat,
		Places places,
		Upload upload) {

	public record Cors(List<String> allowedOrigins) {
	}

	public record Jwt(String secret, Duration accessTtl, Duration refreshTtl) {
	}

	public record Auth(RefreshCookie refreshCookie) {
	}

	public record RefreshCookie(String name, String path, boolean secure, String sameSite) {
	}

	/** 최대 인원, 최대 여행 일수, 핀 색 개수 */
	public record Trip(int maxMembers, int maxDays, int pinColorCount) {
	}

	/** 하이라이트 시간(초) */
	public record Schedule(int highlightSeconds) {
	}

	public record Chat(int pageSize) {
	}

	/** googleApiKey: 구글 Places API 키. 비어 있으면 구글을 부르지 않는다. */
	public record Places(String googleApiKey) {
	}

	public record Upload(String dir) {
	}
}
