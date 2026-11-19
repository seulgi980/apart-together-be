package com.aparttogether.apart_together_be.trip;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aparttogether.apart_together_be.support.IntegrationTest;
import com.aparttogether.apart_together_be.support.TestApi;

import tools.jackson.databind.JsonNode;

class TripTest extends IntegrationTest {

	private JsonNode trip(Member member, long tripId) {
		return api.get(member.token(), "/api/trips/" + tripId).body();
	}

	@Test
	@DisplayName("[일정 생성] 방을 만들면 같은 트랜잭션에서 공동 일정 1개와 방장의 개인 일정이 생긴다")
	void 일정생성_방을만들면_공동일정과_개인일정이_생긴다() {
		Member owner = signup("지은");
		long tripId = createTrip(owner);

		JsonNode body = trip(owner, tripId);
		assertThat(body.get("ownerUserId").asLong()).isEqualTo(owner.id());
		assertThat(body.get("dayCount").asInt()).isEqualTo(5);
		assertThat(body.get("inviteCode").asString()).hasSize(8);
		assertThat(body.get("members")).hasSize(1);
		assertThat(body.get("members").get(0).get("pinColorNo").asInt()).isEqualTo(1);

		assertThat(jdbc.queryForObject(
				"select count(*) from schedules where trip_id = ? and scope = 'SHARED' and owner_key = 0",
				Integer.class, tripId)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"select count(*) from schedules where trip_id = ? and scope = 'PERSONAL' and owner_key = ?",
				Integer.class, tripId, owner.id())).isEqualTo(1);
	}

	@Test
	@DisplayName("[일정 생성] 개인 일정은 멤버가 참여할 때 만들고, 이미 참여 중이면 다시 만들지 않는다")
	void 일정생성_참여할때_개인일정을_만든다() {
		Member owner = signup("지은");
		Member minsu = signup("민수");
		long tripId = createTrip(owner);
		String code = inviteCode(owner, tripId);

		TestApi.Response joined = join(minsu, code.toLowerCase());
		assertThat(joined.body().get("status").asString()).isEqualTo("JOINED");
		// 이미 참여 중이면 해당 여행방으로 이동할 수 있게 ALREADY를 돌려준다 (F1-4)
		assertThat(join(minsu, code).body().get("status").asString()).isEqualTo("ALREADY");

		assertThat(jdbc.queryForObject("select count(*) from schedules where trip_id = ? and owner_key = ?",
				Integer.class, tripId, minsu.id())).isEqualTo(1);
		assertThat(code(join(minsu, "NOPE0000"))).isEqualTo("INVITE_NOT_FOUND");
	}

	@Test
	@DisplayName("[여행 기간] 최대 일수(99일)를 넘는 기간은 만들 때도 고칠 때도 거절한다")
	void 여행기간_최대일수를_넘을수없다() {
		Member owner = signup("지은");

		// 2026-01-01 ~ 2026-04-09 = 99일, 04-10 = 100일
		assertThat(api.post(owner.token(), "/api/trips",
				TestApi.json("title", "긴 여행", "startDate", "2026-01-01", "endDate", "2026-04-09")).status())
				.isEqualTo(201);
		TestApi.Response tooLong = api.post(owner.token(), "/api/trips",
				TestApi.json("title", "긴 여행", "startDate", "2026-01-01", "endDate", "2026-04-10"));
		assertThat(code(tooLong)).isEqualTo("TRIP_TOO_LONG");
		assertThat(tooLong.body().get("maxDays").asInt()).isEqualTo(99);

		long tripId = createTrip(owner);
		assertThat(code(api.patch(owner.token(), "/api/trips/" + tripId,
				TestApi.json("title", "도쿄", "startDate", "2026-01-01", "endDate", "2026-04-10"))))
				.isEqualTo("TRIP_TOO_LONG");
		assertThat(code(api.patch(owner.token(), "/api/trips/" + tripId,
				TestApi.json("title", "도쿄", "startDate", "2026-01-05", "endDate", "2026-01-01"))))
				.isEqualTo("VALIDATION_FAILED");
	}

	@Test
	@DisplayName("[인원 제한] ACTIVE 멤버가 최대 인원(10)이면 참여를 거절한다. 누가 나가면 다시 들어올 수 있다")
	void 인원제한_최대인원을_넘으면_참여거절() {
		Member owner = signup("방장");
		long tripId = createTrip(owner);
		String code = inviteCode(owner, tripId);
		List<Member> joined = new ArrayList<>();
		for (int i = 2; i <= 10; i++) {
			Member member = signup("멤버" + i);
			assertThat(join(member, code).status()).isEqualTo(200);
			joined.add(member);
		}
		assertThat(trip(owner, tripId).get("members")).hasSize(10);

		Member late = signup("열한번째");
		TestApi.Response full = join(late, code);
		assertThat(full.status()).isEqualTo(409);
		assertThat(code(full)).isEqualTo("TRIP_FULL");

		assertThat(api.post(joined.get(0).token(), "/api/trips/" + tripId + "/leave", null).status()).isEqualTo(204);
		assertThat(join(late, code).body().get("status").asString()).isEqualTo("JOINED");
	}

	@Test
	@DisplayName("[핀 색] 방 안에서 겹치지 않게 자동 배정하고, 다른 멤버가 쓰는 색으로는 바꿀 수 없다")
	void 핀색_겹치지않게_자동배정() {
		Member owner = signup("지은");
		Member minsu = signup("민수");
		Member hana = signup("하나");
		long tripId = createTrip(owner);
		joinTrip(owner, tripId, minsu, hana);

		List<Integer> colors = new ArrayList<>();
		trip(owner, tripId).get("members").forEach(member -> colors.add(member.get("pinColorNo").asInt()));
		assertThat(colors).containsExactly(1, 2, 3);

		assertThat(code(api.patch(minsu.token(), "/api/trips/" + tripId + "/members/me",
				TestApi.json("pinColorNo", 1)))).isEqualTo("PIN_COLOR_TAKEN");
		assertThat(code(api.patch(minsu.token(), "/api/trips/" + tripId + "/members/me",
				TestApi.json("pinColorNo", 11)))).isEqualTo("VALIDATION_FAILED");
		assertThat(api.patch(minsu.token(), "/api/trips/" + tripId + "/members/me",
				TestApi.json("pinColorNo", 7)).body().get("pinColorNo").asInt()).isEqualTo(7);

		// 나간 멤버의 색(2는 민수가 7로 바꿔서 비었음)은 다음 사람이 쓴다
		Member dora = signup("도라");
		joinTrip(owner, tripId, dora);
		JsonNode members = trip(owner, tripId).get("members");
		assertThat(members.get(3).get("pinColorNo").asInt()).isEqualTo(2);
	}

	@Test
	@DisplayName("[권한] 방과 관련된 API는 ACTIVE 멤버인지 DB에서 확인한다. 내보내진 멤버는 토큰이 살아 있어도 거절된다")
	void 권한_ACTIVE멤버만_접근할수있다() {
		Member owner = signup("지은");
		Member minsu = signup("민수");
		Member stranger = signup("남남");
		long tripId = createTrip(owner);
		joinTrip(owner, tripId, minsu);

		assertThat(code(api.get(stranger.token(), "/api/trips/" + tripId))).isEqualTo("NOT_TRIP_MEMBER");
		assertThat(api.get(stranger.token(), "/api/trips/" + tripId).status()).isEqualTo(403);
		assertThat(api.get(stranger.token(), "/api/trips/999999").status()).isEqualTo(403);
		assertThat(api.get(minsu.token(), "/api/trips/" + tripId).status()).isEqualTo(200);

		assertThat(api.delete(owner.token(), "/api/trips/" + tripId + "/members/" + minsu.id()).status())
				.isEqualTo(204);
		// 민수의 access token은 아직 유효하지만 더 이상 멤버가 아니다
		assertThat(api.get(minsu.token(), "/api/users/me").status()).isEqualTo(200);
		assertThat(code(api.get(minsu.token(), "/api/trips/" + tripId))).isEqualTo("NOT_TRIP_MEMBER");
		assertThat(api.get(minsu.token(), "/api/trips").body()).isEmpty();
	}

	@Test
	@DisplayName("[권한] 기간 수정, 내보내기, 초대코드 재발급, 방 삭제는 방장만 할 수 있다 (F1-10)")
	void 권한_방장만_할수있는일() {
		Member owner = signup("지은");
		Member minsu = signup("민수");
		long tripId = createTrip(owner);
		joinTrip(owner, tripId, minsu);
		String base = "/api/trips/" + tripId;

		assertThat(code(api.patch(minsu.token(), base,
				TestApi.json("title", "바꿈", "startDate", "2026-12-01", "endDate", "2026-12-03"))))
				.isEqualTo("OWNER_ONLY");
		assertThat(code(api.post(minsu.token(), base + "/invite-code", null))).isEqualTo("OWNER_ONLY");
		assertThat(code(api.delete(minsu.token(), base + "/members/" + owner.id()))).isEqualTo("OWNER_ONLY");
		assertThat(code(api.delete(minsu.token(), base))).isEqualTo("OWNER_ONLY");
		assertThat(code(api.post(owner.token(), base + "/leave", null))).isEqualTo("OWNER_CANNOT_LEAVE");

		String oldCode = inviteCode(owner, tripId);
		String newCode = api.post(owner.token(), base + "/invite-code", null).body().get("inviteCode").asString();
		assertThat(newCode).isNotEqualTo(oldCode);
		assertThat(code(join(signup("하나"), oldCode))).isEqualTo("INVITE_NOT_FOUND");

		assertThat(api.delete(owner.token(), base).status()).isEqualTo(204);
		assertThat(jdbc.queryForObject("select count(*) from trip_members where trip_id = ?", Integer.class, tripId))
				.isZero();
	}

	@Test
	@DisplayName("[멤버 나가기/내보내기] 행은 남기고 status, pin_color_no(NULL), left_at을 바꾼다")
	void 멤버나가기_행은남기고_상태만바꾼다() {
		Member owner = signup("지은");
		Member minsu = signup("민수");
		long tripId = createTrip(owner);
		joinTrip(owner, tripId, minsu);

		assertThat(api.post(minsu.token(), "/api/trips/" + tripId + "/leave", null).status()).isEqualTo(204);

		var row = jdbc.queryForMap("select status, pin_color_no, left_at from trip_members where trip_id = ? and user_id = ?",
				tripId, minsu.id());
		assertThat(row.get("status")).isEqualTo("LEFT");
		assertThat(row.get("pin_color_no")).isNull();
		assertThat(row.get("left_at")).isNotNull();

		JsonNode body = trip(owner, tripId);
		assertThat(body.get("members")).hasSize(1);
		assertThat(body.get("formerMembers").get(0).get("userId").asLong()).isEqualTo(minsu.id());
		assertThat(body.get("formerMembers").get(0).get("status").asString()).isEqualTo("LEFT");
	}

	@Test
	@DisplayName("[재참여] 스스로 나간(LEFT) 사람은 다시 들어올 수 있다: 새 행을 만들지 않고 기존 행을 되살리며 핀 색과 개인 일정을 새로 받는다")
	void 재참여_나간사람은_기존행을_되살린다() {
		Member owner = signup("지은");
		Member minsu = signup("민수");
		long tripId = createTrip(owner);
		joinTrip(owner, tripId, minsu);
		Long memberRowId = jdbc.queryForObject("select id from trip_members where trip_id = ? and user_id = ?",
				Long.class, tripId, minsu.id());
		Long oldScheduleId = jdbc.queryForObject("select id from schedules where trip_id = ? and owner_key = ?",
				Long.class, tripId, minsu.id());

		api.post(minsu.token(), "/api/trips/" + tripId + "/leave", null);
		assertThat(jdbc.queryForObject("select count(*) from schedules where trip_id = ? and owner_key = ?",
				Integer.class, tripId, minsu.id())).isZero();

		assertThat(join(minsu, inviteCode(owner, tripId)).body().get("status").asString()).isEqualTo("JOINED");

		var row = jdbc.queryForMap("select id, status, pin_color_no, left_at from trip_members where trip_id = ? and user_id = ?",
				tripId, minsu.id());
		assertThat(row.get("id")).isEqualTo(memberRowId);
		assertThat(row.get("status")).isEqualTo("ACTIVE");
		assertThat(row.get("left_at")).isNull();
		assertThat(row.get("pin_color_no")).isNotNull();
		assertThat(jdbc.queryForObject("select count(*) from trip_members where trip_id = ?", Integer.class, tripId))
				.isEqualTo(2);
		Long newScheduleId = jdbc.queryForObject("select id from schedules where trip_id = ? and owner_key = ?",
				Long.class, tripId, minsu.id());
		assertThat(newScheduleId).isNotEqualTo(oldScheduleId);
		assertThat(trip(owner, tripId).get("formerMembers")).isEmpty();
	}

	@Test
	@DisplayName("[재참여] 내보내진(KICKED) 사람은 초대코드가 있어도 다시 들어올 수 없다")
	void 재참여_내보내진사람은_다시들어올수없다() {
		Member owner = signup("지은");
		Member minsu = signup("민수");
		long tripId = createTrip(owner);
		joinTrip(owner, tripId, minsu);

		api.delete(owner.token(), "/api/trips/" + tripId + "/members/" + minsu.id());

		TestApi.Response response = join(minsu, inviteCode(owner, tripId));
		assertThat(response.status()).isEqualTo(403);
		assertThat(code(response)).isEqualTo("KICKED_MEMBER");
		// 초대코드를 재발급해도 마찬가지다
		String newCode = api.post(owner.token(), "/api/trips/" + tripId + "/invite-code", null).body()
				.get("inviteCode").asString();
		assertThat(code(join(minsu, newCode))).isEqualTo("KICKED_MEMBER");
		assertThat(jdbc.queryForObject("select status from trip_members where trip_id = ? and user_id = ?",
				String.class, tripId, minsu.id())).isEqualTo("KICKED");
	}

	@Test
	@DisplayName("여러 여행방에 참여할 수 있고 내 여행방 목록에 모두 나온다 (F1-9)")
	void listMine() {
		Member jieun = signup("지은");
		Member minsu = signup("민수");
		long first = createTrip(jieun);
		long second = createTrip(minsu, "2027-01-01", "2027-01-03");
		joinTrip(minsu, second, jieun);

		JsonNode list = api.get(jieun.token(), "/api/trips").body();
		assertThat(list).hasSize(2);
		assertThat(list.get(0).get("id").asLong()).isEqualTo(first);
		assertThat(list.get(1).get("id").asLong()).isEqualTo(second);
		assertThat(list.get(1).get("members")).hasSize(2);
	}
}
