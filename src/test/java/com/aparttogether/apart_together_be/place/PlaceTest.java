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
	@DisplayName("장소 카드를 등록하면 공동 카드는 dedupe_owner_id=0, 나만 카드는 등록자 ID로 저장된다")
	void create() {
		long shared = addPlace(jieun, tripId, "g-sensoji", "센소지", "SHARED");
		long mine = addPlace(jieun, tripId, "g-ramen", "라멘집", "PRIVATE");

		assertThat(jdbc.queryForObject("select dedupe_owner_id from places where id = ?", Long.class, shared)).isZero();
		assertThat(jdbc.queryForObject("select dedupe_owner_id from places where id = ?", Long.class, mine))
				.isEqualTo(jieun.id());

		JsonNode place = api.get(jieun.token(), base + "/" + shared).body();
		assertThat(place.get("name").asString()).isEqualTo("센소지");
		assertThat(place.get("category").asString()).isEqualTo("SIGHTSEEING");
		assertThat(place.get("visibility").asString()).isEqualTo("SHARED");
		assertThat(place.get("createdByUserId").asLong()).isEqualTo(jieun.id());
		assertThat(place.get("latitude").asDouble()).isEqualTo(35.7148);
	}

	@Test
	@DisplayName("영어 장소명과 영어 주소를 함께 저장하고 돌려준다. 보내지 않으면 비어 있다")
	void english() {
		var body = placeBody("g-sensoji", "센소지", "SHARED");
		body.put("nameEn", "Sensō-ji");
		body.put("addressEn", "2-chōme-3-1 Asakusa, Taito City, Tokyo");
		JsonNode created = api.post(jieun.token(), base, body).body();
		assertThat(created.get("nameEn").asString()).isEqualTo("Sensō-ji");
		assertThat(created.get("addressEn").asString()).isEqualTo("2-chōme-3-1 Asakusa, Taito City, Tokyo");
		assertThat(jdbc.queryForObject("select name_en from places where id = ?", String.class,
				created.get("id").asLong())).isEqualTo("Sensō-ji");

		long plain = addPlace(jieun, tripId, "g-ramen", "라멘집", "SHARED");
		JsonNode place = api.get(jieun.token(), base + "/" + plain).body();
		assertThat(place.get("nameEn").isNull()).isTrue();
		assertThat(place.get("addressEn").isNull()).isTrue();
	}

	@Test
	@DisplayName("같은 장소는 같은 공개 범위 안에서 중복 등록할 수 없다. 공동 카드와 나만 카드는 함께 있을 수 있다 (F2-3)")
	void duplicate() {
		addPlace(jieun, tripId, "g-sensoji", "센소지", "SHARED");

		// 등록자가 달라도 공동 카드끼리는 중복
		TestApi.Response duplicated = api.post(minsu.token(), base, placeBody("g-sensoji", "센소지", "SHARED"));
		assertThat(duplicated.status()).isEqualTo(409);
		assertThat(code(duplicated)).isEqualTo("PLACE_DUPLICATE");

		// 같은 장소의 나만 카드는 각자 하나씩 가질 수 있다
		addPlace(jieun, tripId, "g-sensoji", "센소지", "PRIVATE");
		addPlace(minsu, tripId, "g-sensoji", "센소지", "PRIVATE");
		assertThat(code(api.post(jieun.token(), base, placeBody("g-sensoji", "센소지", "PRIVATE"))))
				.isEqualTo("PLACE_DUPLICATE");
	}

	@Test
	@DisplayName("구글맵 링크가 아니면 등록을 거절한다 (F2-7)")
	void notGoogleMaps() {
		var body = placeBody("g-x", "어딘가", "SHARED");
		body.put("googleMapsUrl", "https://blog.naver.com/some-post");
		assertThat(code(api.post(jieun.token(), base, body))).isEqualTo("NOT_GOOGLE_MAPS_URL");
	}

	@Test
	@DisplayName("[권한] 나만(PRIVATE) 카드는 등록자 본인에게만 조회/수정/삭제를 허용한다 (F2-9)")
	void 권한_나만카드는_등록자만_볼수있다() {
		long shared = addPlace(jieun, tripId, "g-sensoji", "센소지", "SHARED");
		long mine = addPlace(jieun, tripId, "g-ramen", "라멘집", "PRIVATE");

		assertThat(api.get(jieun.token(), base).body()).hasSize(2);
		JsonNode minsuList = api.get(minsu.token(), base).body();
		assertThat(minsuList).hasSize(1);
		assertThat(minsuList.get(0).get("id").asLong()).isEqualTo(shared);

		String path = base + "/" + mine;
		assertThat(code(api.get(minsu.token(), path))).isEqualTo("PLACE_NOT_FOUND");
		assertThat(code(api.patch(minsu.token(), path,
				TestApi.json("name", "바꿈", "category", "CAFE")))).isEqualTo("PLACE_NOT_FOUND");
		assertThat(code(api.delete(minsu.token(), path))).isEqualTo("PLACE_NOT_FOUND");
		assertThat(code(api.post(minsu.token(), path + "/references",
				TestApi.json("url", "https://a.com", "title", "글")))).isEqualTo("PLACE_NOT_FOUND");
		assertThat(code(api.post(minsu.token(), path + "/promote", null))).isEqualTo("PLACE_NOT_FOUND");

		// 본인은 수정/삭제할 수 있다
		assertThat(api.patch(jieun.token(), path, TestApi.json("name", "라멘 맛집", "category", "RESTAURANT",
				"memo", "점심")).body().get("name").asString()).isEqualTo("라멘 맛집");
		assertThat(api.delete(jieun.token(), path).status()).isEqualTo(204);
	}

	@Test
	@DisplayName("[권한] 방의 멤버가 아니면 장소 카드를 볼 수도 만들 수도 없다")
	void 권한_멤버가아니면_접근불가() {
		Member stranger = signup("남남");
		assertThat(code(api.get(stranger.token(), base))).isEqualTo("NOT_TRIP_MEMBER");
		assertThat(code(api.post(stranger.token(), base, placeBody("g-a", "가", "SHARED")))).isEqualTo("NOT_TRIP_MEMBER");
	}

	@Test
	@DisplayName("[투표] 공동(SHARED) 카드에만 투표할 수 있다. 나만 카드에는 투표 행을 만들지 않는다")
	void 투표_공동카드에만_허용한다() {
		long shared = addPlace(jieun, tripId, "g-sensoji", "센소지", "SHARED");
		long mine = addPlace(jieun, tripId, "g-ramen", "라멘집", "PRIVATE");

		assertThat(code(api.put(jieun.token(), base + "/" + mine + "/vote", TestApi.json("vote", "LIKE"))))
				.isEqualTo("VOTE_NOT_ALLOWED");
		assertThat(jdbc.queryForObject("select count(*) from place_votes", Integer.class)).isZero();

		api.put(jieun.token(), base + "/" + shared + "/vote", TestApi.json("vote", "LIKE"));
		api.put(minsu.token(), base + "/" + shared + "/vote", TestApi.json("vote", "LIKE"));
		// 언제든 바꿀 수 있고 둘 중 하나만 선택된다 (F3-1)
		JsonNode votes = api.put(minsu.token(), base + "/" + shared + "/vote", TestApi.json("vote", "PASS")).body()
				.get("votes");
		assertThat(votes.get(String.valueOf(jieun.id())).asString()).isEqualTo("LIKE");
		assertThat(votes.get(String.valueOf(minsu.id())).asString()).isEqualTo("PASS");
		assertThat(jdbc.queryForObject("select count(*) from place_votes where place_id = ?", Integer.class, shared))
				.isEqualTo(2);
	}

	@Test
	@DisplayName("[투표] 나만 카드를 공동으로 올리면 visibility=SHARED, dedupe_owner_id=0으로 함께 바뀌고 투표 대상이 된다")
	void 투표_공동으로올리기() {
		long mine = addPlace(jieun, tripId, "g-ramen", "라멘집", "PRIVATE");

		TestApi.Response promoted = api.post(jieun.token(), base + "/" + mine + "/promote", null);
		assertThat(promoted.status()).isEqualTo(200);
		assertThat(promoted.body().get("visibility").asString()).isEqualTo("SHARED");
		var row = jdbc.queryForMap("select visibility, dedupe_owner_id from places where id = ?", mine);
		assertThat(row.get("visibility")).isEqualTo("SHARED");
		assertThat(row.get("dedupe_owner_id")).isEqualTo(0L);

		assertThat(api.get(minsu.token(), base).body()).hasSize(1);
		assertThat(api.put(minsu.token(), base + "/" + mine + "/vote", TestApi.json("vote", "LIKE")).status())
				.isEqualTo(200);
	}

	@Test
	@DisplayName("[투표] 이미 같은 장소의 공동 카드가 있으면 공동으로 올릴 수 없다: 이미 공동카드로 등록되어 있습니다 (F2-10)")
	void 투표_이미공동카드가있으면_올릴수없다() {
		addPlace(minsu, tripId, "g-ramen", "라멘집", "SHARED");
		long mine = addPlace(jieun, tripId, "g-ramen", "라멘집", "PRIVATE");

		TestApi.Response response = api.post(jieun.token(), base + "/" + mine + "/promote", null);
		assertThat(response.status()).isEqualTo(409);
		assertThat(code(response)).isEqualTo("PLACE_ALREADY_SHARED");
		assertThat(response.body().get("message").asString()).isEqualTo("이미 공동카드로 등록되어 있습니다");
		assertThat(jdbc.queryForObject("select visibility from places where id = ?", String.class, mine))
				.isEqualTo("PRIVATE");
	}

	@Test
	@DisplayName("[투표] 공동 카드를 나만 카드로 되돌릴 수 없다: 수정 API는 공개 범위를 바꾸지 않는다")
	void 투표_공동카드는_나만카드로_되돌릴수없다() {
		long shared = addPlace(jieun, tripId, "g-sensoji", "센소지", "SHARED");

		var body = TestApi.json("name", "센소지", "category", "SIGHTSEEING", "visibility", "PRIVATE");
		assertThat(api.patch(jieun.token(), base + "/" + shared, body).body().get("visibility").asString())
				.isEqualTo("SHARED");
		assertThat(jdbc.queryForObject("select visibility from places where id = ?", String.class, shared))
				.isEqualTo("SHARED");
	}

	@Test
	@DisplayName("참고자료를 추가/수정/삭제할 수 있고, 카드를 삭제하면 참고자료와 투표도 함께 삭제된다 (F2-4, F2-6)")
	void references() {
		long shared = addPlace(jieun, tripId, "g-sensoji", "센소지", "SHARED");
		String path = base + "/" + shared;

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
