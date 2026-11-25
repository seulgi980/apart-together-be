package com.aparttogether.apart_together_be.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;

/**
 * 실제 서버를 임의 포트로 띄우고 HTTP로 부르는 통합 테스트의 바탕.
 * MySQL이 필요하다. 접속 정보는 TEST_DB_URL / TEST_DB_USERNAME / TEST_DB_PASSWORD 환경 변수로 받는다.
 * 시작할 때 db/*.sql로 테이블을 새로 만들고, 테스트마다 데이터를 비운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTest {

	private static final String[] TABLES = { "refresh_tokens", "chat_messages", "schedule_items", "schedules",
			"place_votes", "place_references", "places", "trip_members", "trips", "users" };
	private static final AtomicInteger SEQUENCE = new AtomicInteger();

	@Value("${local.server.port}")
	protected int port;

	@Autowired
	protected JdbcTemplate jdbc;

	protected TestApi api;

	@BeforeEach
	void resetDatabase() {
		api = new TestApi("http://localhost:" + port);
		jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
		for (String table : TABLES) {
			jdbc.execute("TRUNCATE TABLE " + table);
		}
		jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
	}

	/** 가입한 회원 */
	public record Member(long id, String loginId, String nickname, String token, String refreshCookie) {
	}

	protected Member signup(String nickname) {
		String loginId = "user_" + SEQUENCE.incrementAndGet();
		TestApi.Response response = api.post(null, "/api/auth/signup",
				TestApi.json("loginId", loginId, "password", "password1!", "nickname", nickname));
		assertThat(response.status()).isEqualTo(201);
		return new Member(response.body().get("user").get("id").asLong(), loginId, nickname,
				response.body().get("accessToken").asString(), response.cookie("refresh_token"));
	}

	/** 방을 만들고 여행방 ID를 돌려준다 */
	protected long createTrip(Member owner, String startDate, String endDate) {
		TestApi.Response response = api.post(owner.token(), "/api/trips",
				TestApi.json("title", "도쿄 여행", "startDate", startDate, "endDate", endDate));
		assertThat(response.status()).isEqualTo(201);
		return response.body().get("id").asLong();
	}

	protected long createTrip(Member owner) {
		return createTrip(owner, "2026-12-01", "2026-12-05");
	}

	protected String inviteCode(Member member, long tripId) {
		return api.get(member.token(), "/api/trips/" + tripId).body().get("inviteCode").asString();
	}

	protected TestApi.Response join(Member member, String inviteCode) {
		return api.post(member.token(), "/api/trips/join", TestApi.json("inviteCode", inviteCode));
	}

	/** 초대코드로 참여시킨다 */
	protected void joinTrip(Member owner, long tripId, Member... others) {
		String code = inviteCode(owner, tripId);
		for (Member other : others) {
			assertThat(join(other, code).status()).isEqualTo(200);
		}
	}

	/** 장소 카드를 등록하고 ID를 돌려준다 */
	protected long addPlace(Member member, long tripId, String placeKey, String name) {
		TestApi.Response response = api.post(member.token(), "/api/trips/" + tripId + "/places",
				placeBody(placeKey, name));
		assertThat(response.status()).as(String.valueOf(response.body())).isEqualTo(201);
		return response.body().get("id").asLong();
	}

	protected tools.jackson.databind.node.ObjectNode placeBody(String placeKey, String name) {
		return TestApi.json("placeKey", placeKey, "name", name, "address", "도쿄 어딘가", "area", "아사쿠사",
				"latitude", 35.7148, "longitude", 139.7967,
				"googleMapsUrl", "https://maps.app.goo.gl/" + placeKey, "category", "SIGHTSEEING");
	}

	protected static String code(TestApi.Response response) {
		return response.body() == null || response.body().get("code") == null ? null
				: response.body().get("code").asString();
	}
}
