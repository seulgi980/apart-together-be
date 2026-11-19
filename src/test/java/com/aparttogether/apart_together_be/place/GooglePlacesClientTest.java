package com.aparttogether.apart_together_be.place;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aparttogether.apart_together_be.place.GooglePlacesClient.FoundPlace;
import com.aparttogether.apart_together_be.place.GooglePlacesClient.PlaceInfo;

import tools.jackson.databind.json.JsonMapper;

/** 구글 Places API (New) 응답 해석. 실제 호출은 하지 않는다 (문서의 응답 모양으로 확인). */
class GooglePlacesClientTest {

	private final JsonMapper json = JsonMapper.builder().build();

	@Test
	@DisplayName("텍스트 검색 응답에서 장소 ID, 이름, 주소, 좌표, 카테고리를 뽑는다")
	void parseFound() {
		FoundPlace found = GooglePlacesClient.parseFound(json.readTree("""
				{ "places": [ {
				    "id": "ChIJ8T1GpMGOGGARDYGSgpooDWw",
				    "displayName": { "text": "센소지", "languageCode": "ko" },
				    "formattedAddress": "일본 〒111-0032 Tokyo, Taito City, Asakusa, 2 Chome−3−1",
				    "location": { "latitude": 35.7147651, "longitude": 139.7966553 },
				    "addressComponents": [
				      { "longText": "2 Chome", "types": ["sublocality_level_3", "sublocality", "political"] },
				      { "longText": "아사쿠사", "types": ["sublocality_level_2", "sublocality", "political"] },
				      { "longText": "다이토구", "types": ["locality", "political"] }
				    ],
				    "addressComponents": [
				      { "longText": "2 Chome", "types": ["sublocality_level_3", "sublocality", "political"] },
				      { "longText": "아사쿠사", "types": ["sublocality_level_2", "sublocality", "political"] },
				      { "longText": "다이토구", "types": ["locality", "political"] }
				    ],
				    "addressComponents": [
				      { "longText": "2 Chome", "types": ["sublocality_level_3", "sublocality", "political"] },
				      { "longText": "아사쿠사", "types": ["sublocality_level_2", "sublocality", "political"] },
				      { "longText": "다이토구", "types": ["locality", "political"] }
				    ],
				    "primaryType": "tourist_attraction",
				    "types": ["tourist_attraction", "place_of_worship", "point_of_interest"]
				} ] }
				""")).orElseThrow();

		assertThat(found.placeId()).isEqualTo("ChIJ8T1GpMGOGGARDYGSgpooDWw");
		assertThat(found.name()).isEqualTo("센소지");
		assertThat(found.address()).contains("Asakusa");
		// 짧은 지역명은 동네 단위를 먼저 고른다
		assertThat(found.area()).isEqualTo("아사쿠사");
		// 짧은 지역명은 동네 단위를 먼저 고른다
		assertThat(found.area()).isEqualTo("아사쿠사");
		// 짧은 지역명은 동네 단위를 먼저 고른다
		assertThat(found.area()).isEqualTo("아사쿠사");
		assertThat(found.latitude()).isEqualByComparingTo("35.714765");
		assertThat(found.category()).isEqualTo(Place.Category.SIGHTSEEING);
		assertThat(GooglePlacesClient.parseFound(json.readTree("{}"))).isEmpty();
	}

	@Test
	@DisplayName("영어로 받은 상세 응답에서 영어 이름과 주소를 뽑는다")
	void parseEnglish() {
		GooglePlacesClient.EnglishText english = GooglePlacesClient.parseEnglish(json.readTree("""
				{ "displayName": { "text": "Sensō-ji", "languageCode": "en" },
				  "formattedAddress": "2-chōme-3-1 Asakusa, Taito City, Tokyo 111-0032, Japan" }
				"""));
		assertThat(english.name()).isEqualTo("Sensō-ji");
		assertThat(english.address()).isEqualTo("2-chōme-3-1 Asakusa, Taito City, Tokyo 111-0032, Japan");

		GooglePlacesClient.EnglishText empty = GooglePlacesClient.parseEnglish(json.readTree("{}"));
		assertThat(empty.name()).isNull();
		assertThat(empty.address()).isNull();
	}

	@Test
	@DisplayName("상세 응답에서 평점, 영업시간, 웹사이트를 뽑고, 없는 값은 비워 둔다")
	void parseInfo() {
		PlaceInfo info = GooglePlacesClient.parseInfo(json.readTree("""
				{ "id": "ChIJ8T1GpMGOGGARDYGSgpooDWw", "rating": 4.5,
				  "websiteUri": "https://www.senso-ji.jp/",
				  "regularOpeningHours": { "weekdayDescriptions": ["월요일: 오전 6:00 ~ 오후 5:00", "화요일: 오전 6:00 ~ 오후 5:00"] } }
				"""));

		assertThat(info.rating()).isEqualTo(4.5);
		assertThat(info.websiteUrl()).isEqualTo("https://www.senso-ji.jp/");
		assertThat(info.weekdayHours()).hasSize(2);

		PlaceInfo empty = GooglePlacesClient.parseInfo(json.readTree("{ \"id\": \"x\" }"));
		assertThat(empty.rating()).isNull();
		assertThat(empty.websiteUrl()).isNull();
		assertThat(empty.weekdayHours()).isEmpty();
	}

	@Test
	@DisplayName("구글 장소 유형을 우리 카테고리로 바꾼다")
	void category() {
		assertThat(GooglePlacesClient.category(List.of("ramen_restaurant", "food"))).isEqualTo(Place.Category.RESTAURANT);
		assertThat(GooglePlacesClient.category(List.of("coffee_shop", "store"))).isEqualTo(Place.Category.CAFE);
		assertThat(GooglePlacesClient.category(List.of("hotel", "lodging"))).isEqualTo(Place.Category.LODGING);
		assertThat(GooglePlacesClient.category(List.of("shopping_mall"))).isEqualTo(Place.Category.SHOPPING);
		assertThat(GooglePlacesClient.category(List.of("buddhist_temple"))).isEqualTo(Place.Category.SIGHTSEEING);
		assertThat(GooglePlacesClient.category(List.of("train_station"))).isEqualTo(Place.Category.ETC);
	}
}
