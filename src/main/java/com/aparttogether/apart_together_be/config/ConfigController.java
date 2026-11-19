package com.aparttogether.apart_together_be.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 프론트가 함께 써야 하는 설정값 (코드에 박지 않는다) */
@RestController
public class ConfigController {

	/** serverTime: 서버의 현재 시각. 화면이 하이라이트 시간을 잴 때 기기 시계와의 차이를 맞추는 데 쓴다. */
	public record ConfigResponse(int maxMembers, int maxTripDays, int pinColorCount, int highlightSeconds,
			java.time.Instant serverTime) {
	}

	private final AppProperties properties;

	public ConfigController(AppProperties properties) {
		this.properties = properties;
	}

	@GetMapping("/api/config")
	public ConfigResponse config() {
		return new ConfigResponse(properties.trip().maxMembers(), properties.trip().maxDays(),
				properties.trip().pinColorCount(), properties.schedule().highlightSeconds(), java.time.Instant.now());
	}
}
