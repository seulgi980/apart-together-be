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
	@DisplayName("모바일 공유 링크(q=장소명, 주소)는 좌표가 없으므로 q 전체를 검색어로 쓴다")
	void parseMobileUrl() {
		String mobile = "https://www.google.com/maps?q=%EC%84%BC%EC%86%8C%EC%A7%80,+2+Chome-3-1+Asakusa&ftid=0x60188EC1A4463DF1:0x6c0d289a8292810d&entry=gps";
		Parsed parsed = PlaceLinkResolver.parse(mobile);

		assertThat(parsed.name()).isEqualTo("센소지, 2 Chome-3-1 Asakusa");
		assertThat(parsed.latitude()).isNull();
	}

	@Test
	@DisplayName("모바일 앱 공유 링크에서 장소명과 번지가 붙어 와도 도시까지 검색어에 남긴다")
	void parseMobileUrlWithoutCommaAfterName() {
		// maps.app.goo.gl/KUicjK2VWW7hWaYz8 가 풀린 주소
		String mobile = "https://maps.google.com?q=%ED%9B%84%EC%A7%80%EB%A0%88%EC%9D%B4%EC%B9%B4+18-15+Omoricho,+Hakodate,"
				+ "+Hokkaido+040-0034+%EC%9D%BC%EB%B3%B8&ftid=0x5f9ef375060c0001:0x2eeccc04f9095c30&entry=gps&g_st=ic";
		Parsed parsed = PlaceLinkResolver.parse(mobile);

		assertThat(parsed.name()).isEqualTo("후지레이카 18-15 Omoricho, Hakodate, Hokkaido 040-0034 일본");
		assertThat(parsed.latitude()).isNull();
	}

	@Test
	@DisplayName("PC에서 복사한 링크는 장소명과 핀 좌표를 뽑는다")
	void parsePcUrl() {
		// maps.app.goo.gl/5yKBvQAHskzhfckm8 가 풀린 주소
		String pc = "https://www.google.co.kr/maps/place/%ED%9B%84%EC%A7%80%EB%A0%88%EC%9D%B4%EC%B9%B4/@41.7697455,140.7323658,17z"
				+ "/data=!3m1!4b1!4m6!3m5!1s0x5f9ef375060c0001:0x2eeccc04f9095c30!8m2!3d41.7697415!4d140.7349407!16s%2Fg%2F1tkmmd31";
		Parsed parsed = PlaceLinkResolver.parse(pc);

		assertThat(parsed.name()).isEqualTo("후지레이카");
		assertThat(parsed.latitude()).isEqualByComparingTo("41.769742");
		assertThat(parsed.longitude()).isEqualByComparingTo("140.734941");
	}

	@Test
	@DisplayName("@0,0 처럼 비어 있는 좌표는 위치 힌트로 쓰지 않는다")
	void ignoreZeroCoordinates() {
		// 카톡 미리보기가 바꾼 주소
		String kakao = "https://www.google.com/maps/place/%ED%9B%84%EC%A7%80%EB%A0%88%EC%9D%B4%EC%B9%B4+18-15+Omoricho,"
				+ "+Hakodate,+Hokkaido+040-0034+%EC%9D%BC%EB%B3%B8/@0,0,22z/data=!4m2!3m1!1s0x5f9ef375060c0001:0x2eeccc04f9095c30";
		Parsed parsed = PlaceLinkResolver.parse(kakao);

		assertThat(parsed.name()).isEqualTo("후지레이카 18-15 Omoricho, Hakodate, Hokkaido 040-0034 일본");
		assertThat(parsed.latitude()).isNull();
		assertThat(parsed.longitude()).isNull();
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
