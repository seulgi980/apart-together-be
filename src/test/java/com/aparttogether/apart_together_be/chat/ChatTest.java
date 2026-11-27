package com.aparttogether.apart_together_be.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aparttogether.apart_together_be.support.IntegrationTest;
import com.aparttogether.apart_together_be.support.TestApi;

import tools.jackson.databind.JsonNode;

class ChatTest extends IntegrationTest {

	private Member jieun;
	private Member minsu;
	private long tripId;
	private String chat;

	@BeforeEach
	void setUp() {
		jieun = signup("지은");
		minsu = signup("민수");
		tripId = createTrip(jieun);
		joinTrip(jieun, tripId, minsu);
		chat = "/api/trips/" + tripId + "/chat";
	}

	private long send(Member member, String text) {
		TestApi.Response response = api.post(member.token(), chat + "/messages", TestApi.json("content", text));
		assertThat(response.status()).isEqualTo(201);
		return response.body().get("id").asLong();
	}

	@Test
	@DisplayName("이전 메시지 조회: 최근 쪽부터 내려주고 before로 위쪽을 이어서 불러온다 (F6-6)")
	void pagedHistory() {
		for (int i = 1; i <= 7; i++) {
			send(i % 2 == 0 ? jieun : minsu, "메시지 " + i);
		}

		JsonNode latest = api.get(jieun.token(), chat + "/messages?limit=3").body();
		assertThat(latest.get("hasMore").asBoolean()).isTrue();
		assertThat(latest.get("messages")).hasSize(3);
		assertThat(latest.get("messages").get(0).get("content").asString()).isEqualTo("메시지 5");
		assertThat(latest.get("messages").get(2).get("content").asString()).isEqualTo("메시지 7");
		assertThat(latest.get("messages").get(2).get("kind").asString()).isEqualTo("USER");
		assertThat(latest.get("messages").get(2).get("senderUserId").asLong()).isEqualTo(minsu.id());
		assertThat(latest.get("messages").get(2).get("createdAt").asString()).endsWith("Z");

		long before = latest.get("messages").get(0).get("id").asLong();
		JsonNode older = api.get(jieun.token(), chat + "/messages?limit=10&before=" + before).body();
		assertThat(older.get("hasMore").asBoolean()).isFalse();
		assertThat(older.get("messages")).hasSize(4);
		assertThat(older.get("messages").get(0).get("content").asString()).isEqualTo("메시지 1");
	}

	@Test
	@DisplayName("안 읽은 수: 남이 보낸 메시지만 세고, 읽음 처리하면 0이 된다 (F6-4)")
	void unreadCount() {
		send(jieun, "안녕");
		send(jieun, "어디 갈까");
		long mine = send(minsu, "내가 보낸 것");

		assertThat(api.get(minsu.token(), chat + "/unread").body().get("unreadCount").asLong()).isEqualTo(2);
		assertThat(api.get(jieun.token(), chat + "/unread").body().get("unreadCount").asLong()).isEqualTo(1);
		// 여행방 목록에도 안 읽은 수가 실린다
		assertThat(api.get(minsu.token(), "/api/trips").body().get(0).get("unreadCount").asLong()).isEqualTo(2);

		JsonNode read = api.post(minsu.token(), chat + "/read", null).body();
		assertThat(read.get("unreadCount").asLong()).isZero();
		assertThat(read.get("lastReadMessageId").asLong()).isEqualTo(mine);

		send(jieun, "새 메시지");
		assertThat(api.get(minsu.token(), chat + "/unread").body().get("unreadCount").asLong()).isEqualTo(1);
		// 읽은 위치는 뒤로 돌아가지 않는다
		api.post(minsu.token(), chat + "/read", TestApi.json("lastReadMessageId", 1));
		assertThat(api.get(minsu.token(), chat + "/unread").body().get("lastReadMessageId").asLong())
				.isEqualTo(mine);
	}

	@Test
	@DisplayName("[채팅] 장소 카드 공유는 같은 여행방의 카드만 허용한다")
	void 채팅_같은방카드만_공유할수있다() {
		long shared = addPlace(jieun, tripId, "g-sensoji", "센소지");
		long otherTrip = createTrip(minsu);
		long otherPlace = addPlace(minsu, otherTrip, "g-other", "다른 방 장소");

		TestApi.Response ok = api.post(jieun.token(), chat + "/messages", TestApi.json("sharedPlaceId", shared));
		assertThat(ok.status()).isEqualTo(201);
		assertThat(ok.body().get("kind").asString()).isEqualTo("PLACE");
		assertThat(ok.body().get("sharedPlaceId").asLong()).isEqualTo(shared);

		assertThat(code(api.post(jieun.token(), chat + "/messages", TestApi.json("sharedPlaceId", 999999))))
				.isEqualTo("SHARE_NOT_ALLOWED");
		assertThat(code(api.post(minsu.token(), chat + "/messages", TestApi.json("sharedPlaceId", otherPlace))))
				.isEqualTo("SHARE_NOT_ALLOWED");
		// 카드 등록 알림 1개 + 공유 1개. 거절된 공유는 남지 않는다
		assertThat(jdbc.queryForObject("select count(*) from chat_messages where trip_id = ?", Integer.class, tripId))
				.isEqualTo(2);
	}

	@Test
	@DisplayName("[채팅] 장소 카드를 등록하면 'OO님이 XX 장소 카드를 추가했습니다.' 시스템 메시지가 그 카드 ID와 함께 남는다")
	void 채팅_카드등록_알림() {
		long placeId = addPlace(jieun, tripId, "g-sensoji", "센소지");

		JsonNode messages = api.get(minsu.token(), chat + "/messages").body().get("messages");
		assertThat(messages).hasSize(1);
		JsonNode notice = messages.get(0);
		assertThat(notice.get("kind").asString()).isEqualTo("SYSTEM");
		assertThat(notice.get("senderUserId").isNull()).isTrue();
		assertThat(notice.get("content").asString()).isEqualTo("지은님이 센소지 장소 카드를 추가했습니다.");
		assertThat(notice.get("sharedPlaceId").asLong()).isEqualTo(placeId);
		// 알림은 안 읽은 수에 세지 않는다 (누구에게도)
		assertThat(api.get(minsu.token(), chat + "/unread").body().get("unreadCount").asLong()).isZero();
		assertThat(api.get(jieun.token(), chat + "/unread").body().get("unreadCount").asLong()).isZero();

		// 중복 등록으로 거절되면 알림도 남지 않는다
		api.post(minsu.token(), "/api/trips/" + tripId + "/places", placeBody("g-sensoji", "센소지"));
		assertThat(api.get(minsu.token(), chat + "/messages").body().get("messages")).hasSize(1);
	}

	@Test
	@DisplayName("[채팅] 안 읽은 수는 말풍선 메시지(일반, 장소 카드 공유)만 센다. 시스템 알림은 세지 않는다")
	void 채팅_시스템알림은_안읽은수에서_뺀다() {
		send(minsu, "민수가 보낸 메시지");
		long placeId = addPlace(minsu, tripId, "g-sensoji", "센소지");
		api.post(minsu.token(), "/api/trips/" + tripId + "/schedules/shared/items",
				TestApi.json("dayNo", 1, "placeId", placeId));
		api.post(minsu.token(), chat + "/messages", TestApi.json("sharedPlaceId", placeId));

		// 일반 메시지 1 + 장소 카드 공유 1. 카드 등록 알림, 일정 추가 알림은 세지 않는다
		assertThat(api.get(jieun.token(), chat + "/unread").body().get("unreadCount").asLong()).isEqualTo(2);
		assertThat(api.get(jieun.token(), "/api/trips").body().get(0).get("unreadCount").asLong()).isEqualTo(2);
		assertThat(api.get(minsu.token(), chat + "/unread").body().get("unreadCount").asLong()).isZero();
	}

	@Test
	@DisplayName("공유한 카드가 삭제되면 메시지는 남고 sharedPlaceId만 비어 '삭제된 장소 카드'로 표시할 수 있다 (F6-7)")
	void sharedPlaceDeleted() {
		long shared = addPlace(jieun, tripId, "g-sensoji", "센소지");
		api.post(jieun.token(), chat + "/messages", TestApi.json("sharedPlaceId", shared));
		api.delete(jieun.token(), "/api/trips/" + tripId + "/places/" + shared);

		JsonNode messages = api.get(minsu.token(), chat + "/messages").body().get("messages");
		// [0] 카드 등록 알림, [1] 공유 메시지. 둘 다 카드 ID가 비고, 알림 글은 그대로 남는다
		assertThat(messages.get(0).get("kind").asString()).isEqualTo("SYSTEM");
		assertThat(messages.get(0).get("sharedPlaceId").isNull()).isTrue();
		JsonNode message = messages.get(1);
		assertThat(message.get("kind").asString()).isEqualTo("PLACE");
		assertThat(message.get("sharedPlaceId").isNull()).isTrue();
	}

	@Test
	@DisplayName("빈 메시지와 20000자를 넘는 메시지는 거절하고(20000자는 받는다), 멤버가 아니면 채팅을 읽거나 쓸 수 없다")
	void validationAndPermission() {
		assertThat(code(api.post(jieun.token(), chat + "/messages", TestApi.json("content", "   "))))
				.isEqualTo("VALIDATION_FAILED");
		assertThat(code(api.post(jieun.token(), chat + "/messages", TestApi.json("content", "가".repeat(20001)))))
				.isEqualTo("VALIDATION_FAILED");
		assertThat(api.post(jieun.token(), chat + "/messages", TestApi.json("content", "가".repeat(20000))).status())
				.isEqualTo(201);

		Member stranger = signup("남남");
		assertThat(code(api.get(stranger.token(), chat + "/messages"))).isEqualTo("NOT_TRIP_MEMBER");
		assertThat(code(api.post(stranger.token(), chat + "/messages", TestApi.json("content", "침입"))))
				.isEqualTo("NOT_TRIP_MEMBER");
		assertThat(code(api.get(stranger.token(), chat + "/unread"))).isEqualTo("NOT_TRIP_MEMBER");
	}
}
