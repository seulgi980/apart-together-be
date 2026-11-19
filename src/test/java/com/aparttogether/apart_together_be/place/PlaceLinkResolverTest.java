package com.aparttogether.apart_together_be.place;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aparttogether.apart_together_be.place.PlaceLinkResolver.Parsed;

class PlaceLinkResolverTest {

	private static final String SENSOJI = "https://www.google.com/maps/place/Sens%C5%8D-ji/@35.7147651,139.7940931,17z"
			+ "/data=!3m1!4b1!4m6!3m5!1s0x60188ec1a4463df1:0x6c0d289a8292810d!8m2!3d35.7147651!4d139.7966553!16zL20vMDNfeWc";

	@Test
	@DisplayName("긴 구글맵 주소에서 검색어로 쓸 장소명과 핀 좌표를 뽑는다")
	void parseLongUrl() {
		Parsed parsed = PlaceLinkResolver.parse(SENSOJI);

		assertThat(parsed.name()).isEqualTo("Sensō-ji");
		// 화면 중심(@)이 아니라 핀(!3d!4d)의 좌표
		assertThat(parsed.latitude()).isEqualByComparingTo("35.714765");
		assertThat(parsed.longitude()).isEqualByComparingTo("139.796655");
	}

	@Test
	@DisplayName("모바일 공유 링크(q=장소명, 주소)에서는 장소명만 뽑는다")
	void parseMobileUrl() {
		String mobile = "https://www.google.com/maps?q=%EC%84%BC%EC%86%8C%EC%A7%80,+2+Chome-3-1+Asakusa&ftid=0x60188EC1A4463DF1:0x6c0d289a8292810d&entry=gps";
		Parsed parsed = PlaceLinkResolver.parse(mobile);

		assertThat(parsed.name()).isEqualTo("센소지");
		assertThat(parsed.latitude()).isNull();
	}

	@Test
	@DisplayName("구글맵 링크 판단 (F2-7)")
	void isGoogleMapsUrl() {
		assertThat(GoogleMapsUrls.isGoogleMapsUrl("https://maps.app.goo.gl/abc123")).isTrue();
		assertThat(GoogleMapsUrls.isGoogleMapsUrl(SENSOJI)).isTrue();
		assertThat(GoogleMapsUrls.isGoogleMapsUrl("https://www.google.com/search?q=maps")).isFalse();
		assertThat(GoogleMapsUrls.isGoogleMapsUrl("https://blog.naver.com/a/1")).isFalse();
		assertThat(GoogleMapsUrls.isGoogleMapsUrl("https://maps.app.goo.gl.evil.com/abc")).isFalse();
		assertThat(GoogleMapsUrls.isGoogleMapsUrl("javascript:alert(1)")).isFalse();
		assertThat(GoogleMapsUrls.isGoogleMapsUrl("그냥 글자")).isFalse();
	}
}
