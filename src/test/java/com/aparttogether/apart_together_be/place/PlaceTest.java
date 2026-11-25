package com.aparttogether.apart_together_be.place;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aparttogether.apart_together_be.support.IntegrationTest;
import com.aparttogether.apart_together_be.support.TestApi;

import tools.jackson.databind.JsonNode;

class PlaceTest extends IntegrationTest {

	private Member jieun;
	private Member minsu;
	private long tripId;
	private String base;

	@BeforeEach
	void setUp() {
		jieun = signup("지은");
		minsu = signup("민수");
		tripId = createTrip(jieun);
		joinTrip(jieun, tripId, minsu);
		base = "/api/trips/" + tripId + "/places";
	}

	@Test
	@DisplayName("장소 카드를 등록하면 방의 멤버 모두가 보고 쓸 수 있다")
	void create() {
		long placeId = addPlace(jieun, tripId, "g-sensoji", "센소지");

		JsonNode place = api.get(minsu.token(), base + "/" + placeId).body();
		assertThat(place.get("name").asString()).isEqualTo("센소지");
		assertThat(place.get("category").asString()).isEqualTo("SIGHTSEEING");
		assertThat(place.get("createdByUserId").asLong()).isEqualTo(jieun.id());
		assertThat(place.get("latitude").asDouble()).isEqualTo(35.7148);
		assertThat(place.has("visibility")).isFalse();
		assertThat(api.get(minsu.token(), base).body()).hasSize(1);
	}

	@Test
	@DisplayName("영어 장소명, 영어 주소, 영어 지역명을 함께 저장하고 돌려준다. 보내지 않으면 비어 있다")
	void english() {
		var body = placeBody("g-sensoji", "센소지");
		body.put("nameEn", "Sensō-ji");
		body.put("addressEn", "2-chōme-3-1 Asakusa, Taito City, Tokyo");
		body.put("areaEn", "Asakusa");
		JsonNode created = api.post(jieun.token(), base, body).body();
		assertThat(created.get("nameEn").asString()).isEqualTo("Sensō-ji");
		assertThat(created.get("addressEn").asString()).isEqualTo("2-chōme-3-1 Asakusa, Taito City, Tokyo");
		assertThat(created.get("areaEn").asString()).isEqualTo("Asakusa");
		assertThat(created.get("area").asString()).isEqualTo("아사쿠사");
		assertThat(jdbc.queryForObject("select name_en from places where id = ?", String.class,
				created.get("id").asLong())).isEqualTo("Sensō-ji");

		long plain = addPlace(jieun, tripId, "g-ramen", "라멘집");
		JsonNode place = api.get(jieun.token(), base + "/" + plain).body();
		assertThat(place.get("nameEn").isNull()).isTrue();
		assertThat(place.get("addressEn").isNull()).isTrue();
		assertThat(place.get("areaEn").isNull()).isTrue();
	}

	@Test
	@DisplayName("방마다 같은 장소는 1장만 등록할 수 있다. 등록자가 달라도 중복이고, 다른 방에는 등록할 수 있다 (F2-3)")
	void duplicate() {
		addPlace(jieun, tripId, "g-sensoji", "센소지");

		TestApi.Response duplicated = api.post(minsu.token(), base, placeBody("g-sensoji", "센소지"));
		assertThat(duplicated.status()).isEqualTo(409);
		assertThat(code(duplicated)).isEqualTo("PLACE_DUPLICATE");
		assertThat(code(api.post(jieun.token(), base, placeBody("g-sensoji", "센소지")))).isEqualTo("PLACE_DUPLICATE");

		// 예전 화면이 공개 범위를 보내도 무시하고 똑같이 중복으로 막는다
		var withVisibility = placeBody("g-sensoji", "센소지");
		withVisibility.put("visibility", "PRIVATE");
		assertThat(code(api.post(minsu.token(), base, withVisibility))).isEqualTo("PLACE_DUPLICATE");

		long otherTrip = createTrip(minsu);
		addPlace(minsu, otherTrip, "g-sensoji", "센소지");
		assertThat(jdbc.queryForObject("select count(*) from places where place_key = 'g-sensoji'", Integer.class))
				.isEqualTo(2);
	}

	@Test
	@DisplayName("구글맵 링크가 아니면 등록을 거절한다 (F2-7)")
	void notGoogleMaps() {
		var body = placeBody("g-x", "어딘가");
		body.put("googleMapsUrl", "https://blog.naver.com/some-post");
		assertThat(code(api.post(jieun.token(), base, body))).isEqualTo("NOT_GOOGLE_MAPS_URL");
	}

	@Test
	@DisplayName("[권한] 방의 멤버라면 누가 등록한 카드든 수정/삭제/참고자료 추가를 할 수 있다")
	void 권한_멤버는_모든카드를_수정할수있다() {
		long placeId = addPlace(jieun, tripId, "g-ramen", "라멘집");
		String path = base + "/" + placeId;

		assertThat(api.patch(minsu.token(), path, TestApi.json("name", "라멘 맛집", "category", "RESTAURANT",
				"memo", "점심")).body().get("name").asString()).isEqualTo("라멘 맛집");
		assertThat(api.post(minsu.token(), path + "/references",
				TestApi.json("url", "https://a.com", "title", "글")).status()).isEqualTo(201);
		assertThat(api.delete(minsu.token(), path).status()).isEqualTo(204);
	}

	@Test
	@DisplayName("[권한] 방의 멤버가 아니면 장소 카드를 볼 수도 만들 수도 없고, 다른 방의 카드는 없는 카드다")
	void 권한_멤버가아니면_접근불가() {
		Member stranger = signup("남남");
		assertThat(code(api.get(stranger.token(), base))).isEqualTo("NOT_TRIP_MEMBER");
		assertThat(code(api.post(stranger.token(), base, placeBody("g-a", "가")))).isEqualTo("NOT_TRIP_MEMBER");

		long otherTrip = createTrip(minsu);
		long otherPlace = addPlace(minsu, otherTrip, "g-other", "다른 방 장소");
		assertThat(code(api.get(jieun.token(), base + "/" + otherPlace))).isEqualTo("PLACE_NOT_FOUND");
	}

	@Test
	@DisplayName("[투표] 모든 카드에 투표할 수 있고, 언제든 바꾸거나 취소할 수 있다 (F3-1)")
	void 투표_모든카드에_허용한다() {
		long placeId = addPlace(jieun, tripId, "g-sensoji", "센소지");
		String vote = base + "/" + placeId + "/vote";

		api.put(jieun.token(), vote, TestApi.json("vote", "LIKE"));
		api.put(minsu.token(), vote, TestApi.json("vote", "LIKE"));
		// 언제든 바꿀 수 있고 둘 중 하나만 선택된다
		JsonNode votes = api.put(minsu.token(), vote, TestApi.json("vote", "PASS")).body().get("votes");
		assertThat(votes.get(String.valueOf(jieun.id())).asString()).isEqualTo("LIKE");
		assertThat(votes.get(String.valueOf(minsu.id())).asString()).isEqualTo("PASS");
		assertThat(jdbc.queryForObject("select count(*) from place_votes where place_id = ?", Integer.class, placeId))
				.isEqualTo(2);

		JsonNode cancelled = api.delete(minsu.token(), vote).body().get("votes");
		assertThat(cancelled.has(String.valueOf(minsu.id()))).isFalse();
	}

	@Test
	@DisplayName("공동으로 올리기 API는 없다")
	void promoteRemoved() {
		long placeId = addPlace(jieun, tripId, "g-ramen", "라멘집");
		assertThat(api.post(jieun.token(), base + "/" + placeId + "/promote", null).status()).isNotEqualTo(200);
	}

	@Test
	@DisplayName("참고자료를 추가/수정/삭제할 수 있고, 카드를 삭제하면 참고자료와 투표도 함께 삭제된다 (F2-4, F2-6)")
	void references() {
		long placeId = addPlace(jieun, tripId, "g-sensoji", "센소지");
		String path = base + "/" + placeId;

		JsonNode added = api.post(minsu.token(), path + "/references",
				TestApi.json("url", "https://blog.naver.com/a/1", "title", "센소지 후기", "memo", "아침에 가기")).body();
		assertThat(added.get("references")).hasSize(1);
		long referenceId = added.get("references").get(0).get("id").asLong();
		assertThat(added.get("references").get(0).get("addedByUserId").asLong()).isEqualTo(minsu.id());

		assertThat(code(api.post(minsu.token(), path + "/references",
				TestApi.json("url", "javascript:alert(1)", "title", "나쁜 링크")))).isEqualTo("VALIDATION_FAILED");

		JsonNode updated = api.patch(jieun.token(), path + "/references/" + referenceId,
				TestApi.json("url", "https://blog.naver.com/a/1", "title", "센소지 꿀팁")).body();
		assertThat(updated.get("references").get(0).get("title").asString()).isEqualTo("센소지 꿀팁");
		assertThat(updated.get("references").get(0).get("memo").isNull()).isTrue();

		api.put(jieun.token(), path + "/vote", TestApi.json("vote", "LIKE"));
		assertThat(api.delete(jieun.token(), path).status()).isEqualTo(204);
		assertThat(jdbc.queryForObject("select count(*) from place_references", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from place_votes", Integer.class)).isZero();
	}

}
