package com.aparttogether.apart_together_be.trip;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aparttogether.apart_together_be.support.IntegrationTest;
import com.aparttogether.apart_together_be.support.TestApi;

import tools.jackson.databind.JsonNode;

/** constraints.sql 3부 [멤버 나가기/내보내기], [재참여] */
class MemberRemovalTest extends IntegrationTest {

	private Member jieun;
	private Member minsu;
	private long tripId;
	private String trip;
	private long sharedByJieun;
	private long sharedByMinsu;
	private long ramenByMinsu;

	/** 민수가 투표, 장소 카드, 참고자료, 개인 일정, 공동 일정 항목, 채팅을 남긴 상태 */
	@BeforeEach
	void setUp() {
		jieun = signup("지은");
		minsu = signup("민수");
		tripId = createTrip(jieun);
		joinTrip(jieun, tripId, minsu);
		trip = "/api/trips/" + tripId;

		sharedByJieun = addPlace(jieun, tripId, "g-sensoji", "센소지");
		sharedByMinsu = addPlace(minsu, tripId, "g-tsukiji", "츠키지 시장");
		ramenByMinsu = addPlace(minsu, tripId, "g-ramen", "라멘집");
		api.put(minsu.token(), trip + "/places/" + sharedByJieun + "/vote", TestApi.json("vote", "PASS"));
		api.put(jieun.token(), trip + "/places/" + sharedByJieun + "/vote", TestApi.json("vote", "LIKE"));
		api.post(minsu.token(), trip + "/places/" + sharedByJieun + "/references",
				TestApi.json("url", "https://blog.naver.com/a/1", "title", "민수가 찾은 글"));
		String personal = trip + "/schedules/" + minsu.id() + "/items";
		api.post(minsu.token(), personal, TestApi.json("dayNo", 1, "placeId", ramenByMinsu));
		api.post(minsu.token(), personal, TestApi.json("dayNo", 2, "title", "혼자 쇼핑"));
		api.post(minsu.token(), trip + "/schedules/shared/items", TestApi.json("dayNo", 1, "placeId", sharedByMinsu));
		api.post(minsu.token(), trip + "/chat/messages", TestApi.json("content", "민수의 채팅"));
	}

	private void assertMinsuDataRemoved() {
		// 2) 그 사용자가 이 여행방에서 한 투표 삭제
		assertThat(jdbc.queryForObject("select count(*) from place_votes where user_id = ?", Integer.class,
				minsu.id())).isZero();
		assertThat(jdbc.queryForObject("select count(*) from place_votes where user_id = ?", Integer.class,
				jieun.id())).isEqualTo(1);
		// 3) 개인 일정과 그 안의 항목 삭제
		assertThat(jdbc.queryForObject("select count(*) from schedules where trip_id = ? and owner_key = ?",
				Integer.class, tripId, minsu.id())).isZero();
		// 장소 카드(개인 일정에만 넣었던 카드도), 참고자료, 채팅, 공동 일정 항목은 남긴다
		assertThat(jdbc.queryForObject("select count(*) from places where id in (?, ?)", Integer.class,
				sharedByMinsu, ramenByMinsu)).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from place_references where added_by_user_id = ?",
				Integer.class, minsu.id())).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from chat_messages where sender_user_id = ?", Integer.class,
				minsu.id())).isEqualTo(1);
		JsonNode items = api.get(jieun.token(), trip + "/schedule-items").body();
		assertThat(items).hasSize(1);
		assertThat(items.get(0).get("scope").asString()).isEqualTo("SHARED");
		// 투표가 지워져 카드 상태 계산에서도 빠진다
		JsonNode votes = api.get(jieun.token(), trip + "/places/" + sharedByJieun).body().get("votes");
		assertThat(votes).hasSize(1);
	}

	@Test
	@DisplayName("[멤버 나가기/내보내기] 나가면 그 사람의 투표, 개인 일정만 삭제하고 장소 카드/참고자료/채팅은 남긴다 (F1-11)")
	void 멤버나가기_투표_개인일정만_삭제한다() {
		assertThat(api.post(minsu.token(), trip + "/leave", null).status()).isEqualTo(204);
		assertMinsuDataRemoved();
		assertThat(jdbc.queryForObject("select status from trip_members where trip_id = ? and user_id = ?",
				String.class, tripId, minsu.id())).isEqualTo("LEFT");
	}

	@Test
	@DisplayName("[멤버 나가기/내보내기] 내보내도 같은 것만 삭제하고 status는 KICKED가 된다")
	void 멤버내보내기_같은것만_삭제한다() {
		assertThat(api.delete(jieun.token(), trip + "/members/" + minsu.id()).status()).isEqualTo(204);
		assertMinsuDataRemoved();
		assertThat(jdbc.queryForObject("select status from trip_members where trip_id = ? and user_id = ?",
				String.class, tripId, minsu.id())).isEqualTo("KICKED");
	}

	@Test
	@DisplayName("[멤버 나가기/내보내기] 다른 여행방에서 한 투표와 카드는 건드리지 않는다 (이 방의 것만)")
	void 멤버나가기_다른방의_데이터는_그대로다() {
		long other = createTrip(jieun, "2027-01-01", "2027-01-03");
		joinTrip(jieun, other, minsu);
		long otherShared = addPlace(jieun, other, "g-x", "다른 방 장소");
		long otherByMinsu = addPlace(minsu, other, "g-y", "다른 방 민수 카드");
		api.put(minsu.token(), "/api/trips/" + other + "/places/" + otherShared + "/vote",
				TestApi.json("vote", "LIKE"));

		api.post(minsu.token(), trip + "/leave", null);

		assertThat(jdbc.queryForObject("select count(*) from place_votes where user_id = ?", Integer.class,
				minsu.id())).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from places where id = ?", Integer.class, otherByMinsu))
				.isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from schedules where trip_id = ? and owner_key = ?",
				Integer.class, other, minsu.id())).isEqualTo(1);
	}

	@Test
	@DisplayName("[재참여] 다시 들어와도 나갈 때 지운 것(투표, 개인 일정 항목)은 되살아나지 않고, 남긴 장소 카드/채팅은 그대로 자기 것이다")
	void 재참여_지운것은_되살아나지_않는다() {
		api.post(minsu.token(), trip + "/leave", null);
		assertThat(join(minsu, inviteCode(jieun, tripId)).body().get("status").asString()).isEqualTo("JOINED");

		assertThat(jdbc.queryForObject("select count(*) from place_votes where user_id = ?", Integer.class,
				minsu.id())).isZero();
		// 개인 일정은 새로 만들어졌지만 비어 있다
		assertThat(jdbc.queryForObject("""
				select count(*) from schedule_items i join schedules s on s.id = i.schedule_id
				where s.trip_id = ? and s.owner_key = ?
				""", Integer.class, tripId, minsu.id())).isZero();
		assertThat(jdbc.queryForObject("select count(*) from schedules where trip_id = ? and owner_key = ?",
				Integer.class, tripId, minsu.id())).isEqualTo(1);

		// 예전에 남긴 기록은 user_id를 그대로 갖고 있어 다시 닉네임으로 보인다 (멤버 목록에 다시 있다)
		JsonNode body = api.get(jieun.token(), trip).body();
		assertThat(body.get("members")).hasSize(2);
		assertThat(body.get("formerMembers")).isEmpty();
		assertThat(api.get(minsu.token(), trip + "/places/" + sharedByMinsu).body().get("createdByUserId").asLong())
				.isEqualTo(minsu.id());
	}

	@Test
	@DisplayName("[재참여] 안 읽은 채팅 수는 다시 들어온 시점의 마지막 메시지에 맞춰 0부터 센다")
	void 재참여_안읽은수는_0부터_센다() {
		api.post(minsu.token(), trip + "/leave", null);
		api.post(jieun.token(), trip + "/chat/messages", TestApi.json("content", "민수 없는 동안 1"));
		api.post(jieun.token(), trip + "/chat/messages", TestApi.json("content", "민수 없는 동안 2"));

		join(minsu, inviteCode(jieun, tripId));
		assertThat(api.get(minsu.token(), trip + "/chat/unread").body().get("unreadCount").asLong()).isZero();

		api.post(jieun.token(), trip + "/chat/messages", TestApi.json("content", "돌아온 뒤"));
		assertThat(api.get(minsu.token(), trip + "/chat/unread").body().get("unreadCount").asLong()).isEqualTo(1);
	}
}
