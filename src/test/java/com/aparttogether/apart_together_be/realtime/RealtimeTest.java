package com.aparttogether.apart_together_be.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aparttogether.apart_together_be.support.IntegrationTest;
import com.aparttogether.apart_together_be.support.StompTestClient;
import com.aparttogether.apart_together_be.support.TestApi;

import tools.jackson.databind.JsonNode;

/**
 * 6단계: 실제 서버(임의 포트)에 WebSocket 클라이언트 두 개를 붙여, 두 사용자가 같은 방에서
 * 채팅과 일정 변경을 실시간으로 받는지 확인한다.
 */
class RealtimeTest extends IntegrationTest {

	private Member jieun;
	private Member minsu;
	private long tripId;
	private String trip;

	@BeforeEach
	void setUp() {
		jieun = signup("지은");
		minsu = signup("민수");
		tripId = createTrip(jieun);
		joinTrip(jieun, tripId, minsu);
		trip = "/api/trips/" + tripId;
	}

	@Test
	@DisplayName("두 사용자가 같은 방에서 WebSocket으로 보낸 채팅을 실시간으로 받는다 (저장된 뒤에 전달된다)")
	void 두_사용자가_채팅을_실시간으로_받는다() throws Exception {
		try (StompTestClient a = StompTestClient.connect(port, jieun.token());
				StompTestClient b = StompTestClient.connect(port, minsu.token())) {
			var forJieun = a.subscribeTrip(tripId);
			var forMinsu = b.subscribeTrip(tripId);

			a.send("/app/trips/" + tripId + "/chat", TestApi.json("content", "내일 몇 시에 볼까?"));

			for (var subscription : List.of(forJieun, forMinsu)) {
				JsonNode event = subscription.await("CHAT_MESSAGE");
				assertThat(event).as("CHAT_MESSAGE 이벤트").isNotNull();
				assertThat(event.get("tripId").asLong()).isEqualTo(tripId);
				assertThat(event.get("actorUserId").asLong()).isEqualTo(jieun.id());
				assertThat(event.get("payload").get("kind").asString()).isEqualTo("USER");
				assertThat(event.get("payload").get("content").asString()).isEqualTo("내일 몇 시에 볼까?");
				assertThat(event.get("payload").get("senderUserId").asLong()).isEqualTo(jieun.id());
				// 이벤트에 실린 ID로 DB에서 찾을 수 있다 = 저장(커밋)이 먼저 끝났다
				assertThat(jdbc.queryForObject("select content from chat_messages where id = ?", String.class,
						event.get("payload").get("id").asLong())).isEqualTo("내일 몇 시에 볼까?");
			}
			// REST로 다시 조회해도 같은 메시지가 있다 (이전 메시지 조회)
			assertThat(api.get(minsu.token(), trip + "/chat/messages").body().get("messages")).hasSize(1);
		}
	}

	@Test
	@DisplayName("두 사용자가 같은 방에서 공동 일정 변경(SCHEDULE_CHANGED)과 시스템 메시지를 실시간으로 받는다")
	void 두_사용자가_일정변경을_실시간으로_받는다() throws Exception {
		long placeId = addPlace(jieun, tripId, "g-osaka", "오사카성");
		try (StompTestClient a = StompTestClient.connect(port, jieun.token());
				StompTestClient b = StompTestClient.connect(port, minsu.token())) {
			var forJieun = a.subscribeTrip(tripId);
			var forMinsu = b.subscribeTrip(tripId);

			// 민수가 REST로 공동 일정에 배치한다
			TestApi.Response added = api.post(minsu.token(), trip + "/schedules/shared/items",
					TestApi.json("dayNo", 3, "placeId", placeId, "startTime", "13:00", "endTime", "15:00"));
			assertThat(added.status()).isEqualTo(201);
			long itemId = added.body().get("id").asLong();

			for (var subscription : List.of(forJieun, forMinsu)) {
				// 같은 트랜잭션에서 남긴 시스템 메시지가 먼저, 일정 변경이 그다음에 온다
				JsonNode chat = subscription.await("CHAT_MESSAGE");
				assertThat(chat).as("시스템 메시지 이벤트").isNotNull();
				assertThat(chat.get("payload").get("kind").asString()).isEqualTo("SYSTEM");
				assertThat(chat.get("payload").get("content").asString())
						.isEqualTo("민수님이 Day 3 13:00~15:00에 오사카성을 추가했습니다.");

				JsonNode event = subscription.await("SCHEDULE_CHANGED");
				assertThat(event).as("SCHEDULE_CHANGED 이벤트").isNotNull();
				assertThat(event.get("actorUserId").asLong()).isEqualTo(minsu.id());
				assertThat(event.get("payload").get("scope").asString()).isEqualTo("SHARED");
				JsonNode item = event.get("payload").get("upserted").get(0);
				assertThat(item.get("id").asLong()).isEqualTo(itemId);
				assertThat(item.get("dayNo").asInt()).isEqualTo(3);
				assertThat(item.get("startTime").asString()).isEqualTo("13:00");
				assertThat(item.get("placeName").asString()).isEqualTo("오사카성");
				assertThat(item.get("lastChangeType").asString()).isEqualTo("ADDED");
				assertThat(item.get("lastChangedByUserId").asLong()).isEqualTo(minsu.id());
			}

			// 지은이 시간을 바꾸면 민수가 받는다
			api.patch(jieun.token(), trip + "/schedule-items/" + itemId,
					TestApi.json("startTime", "14:00", "endTime", "15:00"));
			JsonNode changed = forMinsu.await("SCHEDULE_CHANGED");
			assertThat(changed.get("payload").get("upserted").get(0).get("startTime").asString()).isEqualTo("14:00");
			assertThat(changed.get("payload").get("upserted").get(0).get("lastChangeType").asString())
					.isEqualTo("TIME_CHANGED");

			// 제외하면 removedIds로 온다
			api.delete(jieun.token(), trip + "/schedule-items/" + itemId);
			JsonNode removed = forMinsu.await("SCHEDULE_CHANGED");
			assertThat(removed.get("payload").get("removedIds").get(0).asLong()).isEqualTo(itemId);
		}
	}

	@Test
	@DisplayName("거절된 요청은 이벤트를 뿌리지 않는다 (트랜잭션이 커밋된 변경만 나간다)")
	void 거절된_요청은_이벤트가_없다() throws Exception {
		api.post(jieun.token(), trip + "/schedules/shared/items",
				TestApi.json("dayNo", 1, "title", "오사카성", "startTime", "13:00", "endTime", "15:00"));
		try (StompTestClient b = StompTestClient.connect(port, minsu.token())) {
			var forMinsu = b.subscribeTrip(tripId);

			TestApi.Response rejected = api.post(jieun.token(), trip + "/schedules/shared/items",
					TestApi.json("dayNo", 1, "title", "겹치는 일정", "startTime", "14:00"));
			assertThat(code(rejected)).isEqualTo("SCHEDULE_OVERLAP");

			assertThat(forMinsu.drain(800)).isEmpty();
		}
	}

	@Test
	@DisplayName("장소 카드, 투표, 멤버 변동도 이벤트 종류별로 온다")
	void 카드_투표_멤버_이벤트() throws Exception {
		try (StompTestClient b = StompTestClient.connect(port, minsu.token())) {
			var forMinsu = b.subscribeTrip(tripId);

			long placeId = addPlace(jieun, tripId, "g-sensoji", "센소지");
			// 카드 등록 알림(CHAT_MESSAGE)이 카드(PLACE_UPSERTED)보다 먼저 온다
			JsonNode first = forMinsu.await("CHAT_MESSAGE");
			assertThat(first).as("카드 등록 알림").isNotNull();
			assertThat(first.get("payload").get("kind").asString()).isEqualTo("SYSTEM");
			assertThat(first.get("payload").get("sharedPlaceId").asLong()).isEqualTo(placeId);
			JsonNode upserted = forMinsu.await("PLACE_UPSERTED");
			assertThat(upserted.get("payload").get("name").asString()).isEqualTo("센소지");

			api.put(jieun.token(), trip + "/places/" + placeId + "/vote", TestApi.json("vote", "LIKE"));
			JsonNode vote = forMinsu.await("VOTE_CHANGED");
			assertThat(vote.get("payload").get("placeId").asLong()).isEqualTo(placeId);
			assertThat(vote.get("payload").get("userId").asLong()).isEqualTo(jieun.id());
			assertThat(vote.get("payload").get("vote").asString()).isEqualTo("LIKE");

			Member hana = signup("하나");
			joinTrip(jieun, tripId, hana);
			JsonNode joined = forMinsu.await("MEMBER_JOINED");
			assertThat(joined.get("payload").get("nickname").asString()).isEqualTo("하나");
			assertThat(joined.get("payload").get("pinColorNo").asInt()).isEqualTo(3);

			api.post(hana.token(), trip + "/leave", null);
			JsonNode left = forMinsu.await("MEMBER_REMOVED");
			assertThat(left.get("payload").get("userId").asLong()).isEqualTo(hana.id());
			assertThat(left.get("payload").get("status").asString()).isEqualTo("LEFT");

			api.delete(jieun.token(), trip + "/places/" + placeId);
			assertThat(forMinsu.await("PLACE_DELETED").get("payload").get("placeId").asLong()).isEqualTo(placeId);
		}
	}

	@Test
	@DisplayName("'보는 중' 표시: 보고 있는 Day를 보내면 방 사람들이 받고, 연결이 끊기면 지워진다 (F4-16)")
	void 보는중_표시() throws Exception {
		try (StompTestClient b = StompTestClient.connect(port, minsu.token())) {
			var forMinsu = b.subscribeTrip(tripId);
			StompTestClient a = StompTestClient.connect(port, jieun.token());
			a.subscribeTrip(tripId);

			a.send("/app/trips/" + tripId + "/presence", TestApi.json("day", 3));
			JsonNode viewing = forMinsu.await("PRESENCE_CHANGED");
			assertThat(viewing.get("payload").get("viewers")).hasSize(1);
			assertThat(viewing.get("payload").get("viewers").get(0).get("userId").asLong()).isEqualTo(jieun.id());
			assertThat(viewing.get("payload").get("viewers").get(0).get("day").asInt()).isEqualTo(3);
			// 나중에 들어온 사람은 REST로 현재 상태를 받는다
			assertThat(api.get(minsu.token(), trip + "/presence").body().get("viewers")).hasSize(1);

			a.close();
			JsonNode gone = forMinsu.await("PRESENCE_CHANGED");
			assertThat(gone.get("payload").get("viewers")).isEmpty();
		}
	}

	@Test
	@DisplayName("인증: CONNECT 프레임에 유효한 access token이 없으면 연결을 거절한다")
	void 토큰없이는_연결할수없다() {
		assertThatThrownBy(() -> StompTestClient.connect(port, null)).isNotNull();
		assertThatThrownBy(() -> StompTestClient.connect(port, "invalid-token")).isNotNull();
	}

	@Test
	@DisplayName("인증: 허용하지 않은 주소(Origin)에서 온 WebSocket 연결은 거절한다")
	void 다른_출처의_연결은_거절한다() throws Exception {
		assertThatThrownBy(() -> StompTestClient.connect(port, jieun.token(), "http://evil.example.com")).isNotNull();
		try (StompTestClient ok = StompTestClient.connect(port, jieun.token(), "http://localhost:3000")) {
			assertThat(ok.isConnected()).isTrue();
		}
	}

	@Test
	@DisplayName("[권한] 구독할 때 그 방의 ACTIVE 멤버인지 확인한다: 멤버가 아니면 구독이 거절되고 이벤트를 받지 못한다")
	void 권한_멤버가_아니면_구독할수없다() throws Exception {
		Member stranger = signup("남남");
		try (StompTestClient outsider = StompTestClient.connect(port, stranger.token())) {
			var subscription = outsider.subscribeTrip(tripId);

			api.post(jieun.token(), trip + "/chat/messages", TestApi.json("content", "우리끼리 얘기"));

			assertThat(subscription.drain(800)).isEmpty();
			assertThat(outsider.errors.poll(3, TimeUnit.SECONDS)).isNotNull();
			assertThat(outsider.isConnected()).isFalse();
		}
	}

	@Test
	@DisplayName("[권한] 내보내진 멤버는 연결이 끊기고, 다시 연결해도 그 방을 구독할 수 없다")
	void 권한_내보내진_멤버는_이벤트를_받지_못한다() throws Exception {
		try (StompTestClient kicked = StompTestClient.connect(port, minsu.token())) {
			var subscription = kicked.subscribeTrip(tripId);

			api.delete(jieun.token(), trip + "/members/" + minsu.id());
			assertThat(subscription.await("MEMBER_REMOVED")).isNotNull();
			Thread.sleep(1500);
			assertThat(kicked.isConnected()).isFalse();

			// access token은 아직 유효하므로 연결은 되지만, 구독 단계에서 DB의 멤버 상태로 거절된다
			try (StompTestClient again = StompTestClient.connect(port, minsu.token())) {
				var retry = again.subscribeTrip(tripId);
				api.post(jieun.token(), trip + "/chat/messages", TestApi.json("content", "민수 나간 뒤"));
				assertThat(retry.drain(800)).isEmpty();
				assertThat(again.isConnected()).isFalse();
			}
		}
	}

	@Test
	@DisplayName("WebSocket으로 보낸 요청이 거절되면 보낸 사람에게만 /user/queue/errors로 오류가 간다")
	void 거절은_보낸사람에게만() throws Exception {
		try (StompTestClient a = StompTestClient.connect(port, jieun.token());
				StompTestClient b = StompTestClient.connect(port, minsu.token())) {
			var errorsForJieun = a.subscribe("/user/queue/errors");
			var errorsForMinsu = b.subscribe("/user/queue/errors");

			a.send("/app/trips/" + tripId + "/chat", TestApi.json("content", "   "));

			List<JsonNode> received = errorsForJieun.drain(1000);
			assertThat(received).hasSize(1);
			assertThat(received.get(0).get("code").asString()).isEqualTo("VALIDATION_FAILED");
			assertThat(errorsForMinsu.drain(300)).isEmpty();
		}
	}
}
