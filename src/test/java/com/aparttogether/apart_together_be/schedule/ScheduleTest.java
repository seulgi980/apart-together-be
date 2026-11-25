package com.aparttogether.apart_together_be.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aparttogether.apart_together_be.support.IntegrationTest;
import com.aparttogether.apart_together_be.support.TestApi;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** constraints.sql 3부의 일정 규칙. 테스트 이름 앞의 [..]가 규칙 이름이다. */
class ScheduleTest extends IntegrationTest {

	private Member jieun;
	private Member minsu;
	private long tripId;
	private String trip;

	@BeforeEach
	void setUp() {
		jieun = signup("지은");
		minsu = signup("민수");
		tripId = createTrip(jieun); // 2026-12-01 ~ 12-05 (Day 1 ~ Day 5)
		joinTrip(jieun, tripId, minsu);
		trip = "/api/trips/" + tripId;
	}

	/* ───────── 도우미 ───────── */

	private TestApi.Response addMemo(Member member, String owner, int day, String title, String start, String end,
			Integer index) {
		ObjectNode body = TestApi.json("dayNo", day, "title", title);
		if (start != null) {
			body.put("startTime", start);
		}
		if (end != null) {
			body.put("endTime", end);
		}
		if (index != null) {
			body.put("index", index);
		}
		return api.post(member.token(), trip + "/schedules/" + owner + "/items", body);
	}

	/** 공동 일정에 메모 항목을 넣고 ID를 돌려준다 */
	private long memo(int day, String title, String start, String end) {
		TestApi.Response response = addMemo(jieun, "shared", day, title, start, end, null);
		assertThat(response.status()).as(String.valueOf(response.body())).isEqualTo(201);
		return response.body().get("id").asLong();
	}

	private TestApi.Response addPlaceItem(Member member, String owner, int day, long placeId, String start,
			String end) {
		ObjectNode body = TestApi.json("dayNo", day, "placeId", placeId);
		if (start != null) {
			body.put("startTime", start);
		}
		if (end != null) {
			body.put("endTime", end);
		}
		return api.post(member.token(), trip + "/schedules/" + owner + "/items", body);
	}

	private TestApi.Response patch(Member member, long itemId, ObjectNode body) {
		return api.patch(member.token(), trip + "/schedule-items/" + itemId, body);
	}

	private static ObjectNode time(String start, String end) {
		ObjectNode body = TestApi.MAPPER.createObjectNode();
		body.put("startTime", start);
		body.put("endTime", end);
		return body;
	}

	/** DB의 조회 정렬(schema.sql)대로 읽은 한 Day의 제목 순서 */
	private List<String> timeline(String ownerKey, int day) {
		return jdbc.queryForList("""
				select coalesce(i.title, p.name) from schedule_items i
				join schedules s on s.id = i.schedule_id
				left join places p on p.id = i.place_id
				where s.trip_id = ? and s.owner_key = ? and i.day_no = ?
				order by
				  case when i.start_time is not null then hour(i.start_time) * 60 + minute(i.start_time)
				       when i.anchor_minute is null then 1440
				       else i.anchor_minute end,
				  (i.start_time is null), i.sort_order, i.id
				""", String.class, tripId, ownerKey, day);
	}

	private java.util.Map<String, Object> row(long itemId) {
		return jdbc.queryForMap("select * from schedule_items where id = ?", itemId);
	}

	private List<String> chatTexts() {
		return jdbc.queryForList("select content from chat_messages where trip_id = ? order by id", String.class,
				tripId);
	}

	/* ───────── [권한] ───────── */

	@Test
	@DisplayName("[권한] 다른 멤버의 개인 일정은 읽기 전용이다: 볼 수는 있지만 추가/수정/이동/제외/순서 변경은 거절된다")
	void 권한_다른멤버의_개인일정은_읽기전용() {
		String mine = String.valueOf(jieun.id());
		long itemId = addMemo(jieun, mine, 1, "혼자 쇼핑", "10:00", null, null).body().get("id").asLong();

		// 민수도 지은의 개인 일정을 볼 수 있다
		JsonNode list = api.get(minsu.token(), trip + "/schedule-items").body();
		assertThat(list).hasSize(1);
		assertThat(list.get(0).get("scope").asString()).isEqualTo("PERSONAL");
		assertThat(list.get(0).get("ownerUserId").asLong()).isEqualTo(jieun.id());

		assertThat(code(addMemo(minsu, mine, 1, "끼어들기", null, null, null))).isEqualTo("SCHEDULE_READ_ONLY");
		assertThat(code(patch(minsu, itemId, time("11:00", null)))).isEqualTo("SCHEDULE_READ_ONLY");
		assertThat(code(api.post(minsu.token(), trip + "/schedule-items/" + itemId + "/move",
				TestApi.json("dayNo", 2)))).isEqualTo("SCHEDULE_READ_ONLY");
		assertThat(code(api.delete(minsu.token(), trip + "/schedule-items/" + itemId)))
				.isEqualTo("SCHEDULE_READ_ONLY");
		assertThat(code(api.put(minsu.token(), trip + "/schedules/" + mine + "/days/1/order",
				TestApi.json("orderedIds", List.of(itemId), "movedId", itemId)))).isEqualTo("SCHEDULE_READ_ONLY");
		assertThat(api.put(minsu.token(), trip + "/schedules/" + mine + "/days/1/order",
				TestApi.json("orderedIds", List.of(itemId), "movedId", itemId)).status()).isEqualTo(403);
	}

	@Test
	@DisplayName("[권한] 다른 멤버의 개인 일정에 넣은 항목도 장소명/카테고리가 함께 보인다")
	void 권한_개인일정_항목의_장소명은_보인다() {
		long ramen = addPlace(jieun, tripId, "g-ramen", "라멘집");
		assertThat(addPlaceItem(jieun, String.valueOf(jieun.id()), 1, ramen, null, null).status()).isEqualTo(201);

		JsonNode item = api.get(minsu.token(), trip + "/schedule-items").body().get(0);
		assertThat(item.get("placeName").asString()).isEqualTo("라멘집");
		assertThat(item.get("placeCategory").asString()).isEqualTo("SIGHTSEEING");
	}

	@Test
	@DisplayName("[권한] 방의 멤버가 아니면 일정을 조회/수정할 수 없다")
	void 권한_멤버가아니면_일정접근불가() {
		Member stranger = signup("남남");
		assertThat(code(api.get(stranger.token(), trip + "/schedule-items"))).isEqualTo("NOT_TRIP_MEMBER");
		assertThat(code(addMemo(stranger, "shared", 1, "침입", null, null, null))).isEqualTo("NOT_TRIP_MEMBER");
	}

	/* ───────── [여행 기간] ───────── */

	@Test
	@DisplayName("[여행 기간] 기간을 줄이면 범위를 벗어난 Day의 일정 항목을 확인 후 삭제한다 (F1-8)")
	void 여행기간_줄이면_벗어난_일정을_확인후_삭제한다() {
		long keep = memo(2, "남는 일정", "10:00", null);
		long lost = memo(5, "사라질 일정", "10:00", null);
		addMemo(minsu, String.valueOf(minsu.id()), 4, "개인 일정도 사라짐", null, null, null);
		ObjectNode shorter = TestApi.json("title", "도쿄 여행", "startDate", "2026-12-01", "endDate", "2026-12-03");

		// 확인 없이 보내면 거절하고 지워질 항목 수를 알려준다
		TestApi.Response rejected = api.patch(jieun.token(), trip, shorter);
		assertThat(rejected.status()).isEqualTo(409);
		assertThat(code(rejected)).isEqualTo("TRIP_PERIOD_HAS_ITEMS");
		assertThat(rejected.body().get("itemCount").asInt()).isEqualTo(2);
		assertThat(rejected.body().get("lostDayCount").asInt()).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from schedule_items", Integer.class)).isEqualTo(3);

		shorter.put("deleteOutOfRangeItems", true);
		TestApi.Response accepted = api.patch(jieun.token(), trip, shorter);
		assertThat(accepted.status()).isEqualTo(200);
		assertThat(accepted.body().get("dayCount").asInt()).isEqualTo(3);
		assertThat(jdbc.queryForList("select id from schedule_items", Long.class)).containsExactly(keep);
		assertThat(lost).isNotEqualTo(keep);
		// 줄어든 뒤에는 사라진 Day에 넣을 수 없다
		assertThat(code(addMemo(jieun, "shared", 4, "없는 날", null, null, null))).isEqualTo("INVALID_DAY");
	}

	/* ───────── [일정 검증] ───────── */

	@Test
	@DisplayName("[일정 검증] 시간 겹침: 같은 일정, 같은 Day 안에서 시간 범위가 겹치면 거절하고 확정된 항목의 위치를 알려준다 (F4-19, F4-22)")
	void 일정검증_시간이_겹치면_거절한다() {
		memo(3, "오사카성", "13:00", "15:00");

		TestApi.Response rejected = addMemo(jieun, "shared", 3, "점심", "14:00", "16:00", null);
		assertThat(rejected.status()).isEqualTo(409);
		assertThat(code(rejected)).isEqualTo("SCHEDULE_OVERLAP");
		assertThat(rejected.body().get("message").asString()).isEqualTo("해당 시간에 이미 일정이 있습니다.");
		assertThat(rejected.body().get("conflict").get("dayNo").asInt()).isEqualTo(3);
		assertThat(rejected.body().get("conflict").get("startTime").asString()).isEqualTo("13:00");

		// 다른 Day, 다른 일정(개인)은 상관없다
		assertThat(addMemo(jieun, "shared", 4, "점심", "14:00", "16:00", null).status()).isEqualTo(201);
		assertThat(addMemo(jieun, String.valueOf(jieun.id()), 3, "점심", "14:00", "16:00", null).status())
				.isEqualTo(201);
	}

	@Test
	@DisplayName("[일정 검증] 시간 겹침: 종료시간이 없으면 시작 시각 한 점으로 본다 (범위 안이거나 시작 시각이 같으면 겹침)")
	void 일정검증_종료시간이_없으면_한점으로_본다() {
		memo(1, "미술관", "13:00", "15:00");
		memo(1, "카페", "16:00", null);

		assertThat(code(addMemo(jieun, "shared", 1, "범위 안의 점", "14:00", null, null))).isEqualTo("SCHEDULE_OVERLAP");
		assertThat(code(addMemo(jieun, "shared", 1, "같은 시작", "16:00", null, null))).isEqualTo("SCHEDULE_OVERLAP");
		assertThat(code(addMemo(jieun, "shared", 1, "점을 덮는 범위", "15:30", "16:30", null)))
				.isEqualTo("SCHEDULE_OVERLAP");
		// 시작 시각만 있는 항목끼리는 시작 시각이 다르면 겹치지 않는다
		assertThat(addMemo(jieun, "shared", 1, "다른 점", "16:01", null, null).status()).isEqualTo(201);
		// 범위가 끝나는 시각(15:00)의 점은 범위 밖이다
		assertThat(addMemo(jieun, "shared", 1, "끝나는 시각", "15:00", null, null).status()).isEqualTo(201);
	}

	@Test
	@DisplayName("[일정 검증] 시간 겹침: 맞닿는 경우(13:00~14:00과 14:00~15:00)는 겹침이 아니다")
	void 일정검증_맞닿는_경우는_겹침이_아니다() {
		memo(1, "앞", "13:00", "14:00");
		assertThat(addMemo(jieun, "shared", 1, "뒤", "14:00", "15:00", null).status()).isEqualTo(201);
		assertThat(addMemo(jieun, "shared", 1, "더 앞", "12:00", "13:00", null).status()).isEqualTo(201);
	}

	@Test
	@DisplayName("[일정 검증] 시간 겹침: start_time이 NULL인 항목은 검사에서 제외하고, 시간을 입력하는 시점에 검사한다")
	void 일정검증_시간미정은_제외하고_시간을_넣을때_검사한다() {
		memo(1, "오사카성", "13:00", "15:00");
		long untimed = memo(1, "시간 미정 항목", null, null);
		assertThat(memo(1, "또 미정", null, null)).isPositive();

		TestApi.Response rejected = patch(jieun, untimed, time("14:00", null));
		assertThat(code(rejected)).isEqualTo("SCHEDULE_OVERLAP");
		assertThat(row(untimed).get("start_time")).isNull();
		assertThat(patch(jieun, untimed, time("15:00", "16:00")).status()).isEqualTo(200);
	}

	@Test
	@DisplayName("[일정 검증] 종료시간은 시작시간보다 늦어야 하고, 시작시간 없이 종료시간만 둘 수 없다")
	void 일정검증_시간범위() {
		assertThat(code(addMemo(jieun, "shared", 1, "거꾸로", "15:00", "14:00", null))).isEqualTo("INVALID_TIME_RANGE");
		assertThat(code(addMemo(jieun, "shared", 1, "같은 시각", "15:00", "15:00", null))).isEqualTo("INVALID_TIME_RANGE");
		assertThat(code(addMemo(jieun, "shared", 1, "끝만", null, "14:00", null))).isEqualTo("INVALID_TIME_RANGE");
		assertThat(code(addMemo(jieun, "shared", 1, "형식 오류", "25:99", null, null))).isEqualTo("VALIDATION_FAILED");
		assertThat(code(addMemo(jieun, "shared", 9, "없는 날", null, null, null))).isEqualTo("INVALID_DAY");
	}

	@Test
	@DisplayName("[일정 검증] 중복 배치: 같은 장소는 한 일정에 한 번만. 다른 Day여도 거절한다 (F4-20)")
	void 일정검증_중복배치를_거절한다() {
		long shared = addPlace(jieun, tripId, "g-sensoji", "센소지");
		String personal = String.valueOf(jieun.id());
		assertThat(addPlaceItem(jieun, "shared", 3, shared, "13:00", null).status()).isEqualTo(201);

		TestApi.Response rejected = addPlaceItem(minsu, "shared", 1, shared, null, null);
		assertThat(rejected.status()).isEqualTo(409);
		assertThat(code(rejected)).isEqualTo("SCHEDULE_DUPLICATE");
		assertThat(rejected.body().get("message").asString()).isEqualTo("해당 카드가 이미 배치되어 있습니다.");
		assertThat(rejected.body().get("conflict").get("dayNo").asInt()).isEqualTo(3);
		assertThat(rejected.body().get("conflict").get("startTime").asString()).isEqualTo("13:00");

		// 개인 일정은 별개의 일정이라 넣을 수 있다. 그 안에서는 다시 중복이다.
		assertThat(addPlaceItem(jieun, personal, 1, shared, null, null).status()).isEqualTo(201);
		assertThat(code(addPlaceItem(jieun, personal, 2, shared, null, null))).isEqualTo("SCHEDULE_DUPLICATE");
		// 거절된 요청은 채팅에 기록하지 않는다 (F6-5)
		assertThat(chatTexts()).hasSize(1);
	}

	@Test
	@DisplayName("[일정 검증] 숙소는 체크인/체크아웃 때문에 한 일정에 2번까지 배치할 수 있고, 3번째는 거절한다 (F4-20)")
	void 일정검증_숙소는_두번까지_배치한다() {
		TestApi.Response created = api.post(jieun.token(), "/api/trips/" + tripId + "/places",
				placeBody("g-hotel", "아사쿠사 호텔").put("category", "LODGING"));
		assertThat(created.status()).isEqualTo(201);
		long hotel = created.body().get("id").asLong();

		long checkIn = addPlaceItem(jieun, "shared", 1, hotel, "15:00", null).body().get("id").asLong();
		long checkOut = addPlaceItem(jieun, "shared", 3, hotel, "11:00", null).body().get("id").asLong();
		assertThat(row(checkIn).get("place_slot")).isEqualTo(1);
		assertThat(row(checkOut).get("place_slot")).isEqualTo(2);

		TestApi.Response third = addPlaceItem(minsu, "shared", 2, hotel, null, null);
		assertThat(third.status()).isEqualTo(409);
		assertThat(code(third)).isEqualTo("SCHEDULE_DUPLICATE");

		// 체크인 항목을 빼면 비어 있는 1번 자리를 다시 쓴다
		assertThat(api.delete(jieun.token(), trip + "/schedule-items/" + checkIn).status()).isEqualTo(204);
		long again = addPlaceItem(jieun, "shared", 2, hotel, null, null).body().get("id").asLong();
		assertThat(row(again).get("place_slot")).isEqualTo(1);
	}

	@Test
	@DisplayName("[일정 검증] 일정 항목을 만들 때 place_key는 places.place_key를 복사해서 넣는다. 메모 항목은 NULL")
	void 일정검증_place_key를_복사한다() {
		long placeId = addPlace(jieun, tripId, "g-sensoji", "센소지");
		long placeItem = addPlaceItem(jieun, "shared", 1, placeId, null, null).body().get("id").asLong();
		long memoItem = memo(1, "숙소 체크인", null, null);

		assertThat(row(placeItem).get("place_key")).isEqualTo("g-sensoji");
		// 시간은 현지 시각 그대로 저장한다 (시간대 변환 없음)
		long timed = memo(2, "아침", "09:30", "11:00");
		assertThat(jdbc.queryForObject("select concat(start_time, '~', end_time) from schedule_items where id = ?", String.class, timed)).isEqualTo("09:30:00~11:00:00");
		assertThat(row(placeItem).get("title")).isNull();
		assertThat(row(memoItem).get("place_key")).isNull();
		assertThat(row(memoItem).get("place_id")).isNull();
		assertThat(row(memoItem).get("title")).isEqualTo("숙소 체크인");
	}

	@Test
	@DisplayName("[일정 검증] 방의 카드는 누가 등록했든 공동 일정, 개인 일정 어디에든 넣을 수 있다. 다른 방 카드는 넣을 수 없다")
	void 일정검증_방의_카드는_어느_일정에든_넣는다() {
		long ramen = addPlace(jieun, tripId, "g-ramen", "라멘집");

		assertThat(addPlaceItem(jieun, "shared", 1, ramen, null, null).status()).isEqualTo(201);
		assertThat(addPlaceItem(jieun, String.valueOf(jieun.id()), 1, ramen, null, null).status()).isEqualTo(201);
		// 지은이 등록한 카드를 민수가 자기 개인 일정에 넣을 수 있다
		assertThat(addPlaceItem(minsu, String.valueOf(minsu.id()), 1, ramen, null, null).status()).isEqualTo(201);

		long otherTrip = createTrip(minsu);
		long otherPlace = addPlace(minsu, otherTrip, "g-other", "다른 방 장소");
		assertThat(code(addPlaceItem(minsu, "shared", 1, otherPlace, null, null))).isEqualTo("PLACE_NOT_FOUND");
	}

	@Test
	@DisplayName("[일정 검증] 일정 행을 FOR UPDATE로 잠그므로, 동시에 들어온 겹치는 요청은 먼저 저장된 하나만 성공한다 (F4-18)")
	void 일정검증_동시요청은_하나만_성공한다() throws Exception {
		int requests = 8;
		ExecutorService pool = Executors.newFixedThreadPool(requests);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> futures = new ArrayList<>();
		for (int i = 0; i < requests; i++) {
			Member who = i % 2 == 0 ? jieun : minsu;
			String title = "동시 " + i;
			Callable<Integer> task = () -> {
				start.await();
				return addMemo(who, "shared", 2, title, "10:00", "11:00", null).status();
			};
			futures.add(pool.submit(task));
		}
		start.countDown();
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> future : futures) {
			statuses.add(future.get());
		}
		pool.shutdown();

		assertThat(statuses.stream().filter(status -> status == 201).count()).isEqualTo(1);
		assertThat(statuses.stream().filter(status -> status == 409).count()).isEqualTo(requests - 1);
		assertThat(jdbc.queryForObject("select count(*) from schedule_items", Integer.class)).isEqualTo(1);
	}

	/* ───────── [타임라인 순서] ───────── */

	@Test
	@DisplayName("[타임라인 순서] 장소 풀의 [+] 버튼으로 넣은 항목은 그 Day의 맨 아래 시간 미정으로 넣는다 (anchor_minute=NULL, sort_order=최대값+1)")
	void 타임라인순서_플러스버튼은_맨아래_시간미정() {
		memo(1, "센소지", "09:30", "11:00");
		long first = memo(1, "돈까스집", null, null);
		long second = memo(1, "가챠샵", null, null);

		assertThat(row(first).get("anchor_minute")).isNull();
		assertThat(row(first).get("sort_order")).isEqualTo(0);
		assertThat(row(second).get("anchor_minute")).isNull();
		assertThat(row(second).get("sort_order")).isEqualTo(1);
		assertThat(timeline("0", 1)).containsExactly("센소지", "돈까스집", "가챠샵");
	}

	@Test
	@DisplayName("[타임라인 순서] 틈에 끼워 넣으면 그 자리에 들어가고 주변 시간 미정 항목의 자리값을 다시 매긴다 (anchor=앞 시간 항목의 시작 분, 앞에 없으면 -1)")
	void 타임라인순서_틈에_끼워넣기() {
		memo(1, "센소지", "09:30", "11:00");
		memo(1, "츠키지 시장", "12:00", null);
		// 센소지와 츠키지 사이(1번 자리)에 시간 미정으로 두 개
		long tonkatsu = addMemo(jieun, "shared", 1, "돈까스집", null, null, 1).body().get("id").asLong();
		long gacha = addMemo(jieun, "shared", 1, "가챠샵", null, null, 2).body().get("id").asLong();
		assertThat(timeline("0", 1)).containsExactly("센소지", "돈까스집", "가챠샵", "츠키지 시장");
		assertThat(row(tonkatsu).get("anchor_minute")).isEqualTo(570);
		assertThat(row(tonkatsu).get("sort_order")).isEqualTo(0);
		assertThat(row(gacha).get("anchor_minute")).isEqualTo(570);
		assertThat(row(gacha).get("sort_order")).isEqualTo(1);

		// 둘 사이(2번 자리)에 하나 더 끼우면 가챠샵이 뒤로 밀린다
		addMemo(jieun, "shared", 1, "편의점", null, null, 2);
		assertThat(timeline("0", 1)).containsExactly("센소지", "돈까스집", "편의점", "가챠샵", "츠키지 시장");
		assertThat(row(gacha).get("sort_order")).isEqualTo(2);

		// 맨 위(0번 자리)는 앞에 시간 항목이 없으므로 anchor = -1
		long top = addMemo(jieun, "shared", 1, "아침 산책", null, null, 0).body().get("id").asLong();
		assertThat(row(top).get("anchor_minute")).isEqualTo(-1);
		assertThat(timeline("0", 1)).startsWith("아침 산책", "센소지");
	}

	@Test
	@DisplayName("[타임라인 순서] 틈에 새 항목을 넣을 때 시간을 정했다면 그 시간은 앞뒤 시간 항목 사이에 들어가야 한다")
	void 타임라인순서_틈에_넣는_시간은_앞뒤_사이여야_한다() {
		memo(1, "점심", "15:00", null);
		memo(1, "공연", "17:30", "18:00");
		// 두 항목 사이는 1번 자리: 15:00보다 뒤에 시작하고 17:30까지 끝나야 한다

		assertThat(code(addMemo(jieun, "shared", 1, "너무 이름", "14:00", null, 1))).isEqualTo("SCHEDULE_GAP_MISMATCH");
		assertThat(code(addMemo(jieun, "shared", 1, "너무 늦음", "18:30", null, 1))).isEqualTo("SCHEDULE_GAP_MISMATCH");
		assertThat(code(addMemo(jieun, "shared", 1, "뒤를 넘김", "16:00", "17:40", 1))).isEqualTo("SCHEDULE_OVERLAP");
		assertThat(code(addMemo(jieun, "shared", 1, "앞과 같은 시각", "15:00", null, 1))).isEqualTo("SCHEDULE_OVERLAP");

		assertThat(addMemo(jieun, "shared", 1, "카페", "16:00", "17:30", 1).status()).isEqualTo(201);
		assertThat(timeline("0", 1)).containsExactly("점심", "카페", "공연");
		// 앞 항목에 종료시간이 있으면 그 시각부터 넣을 수 있다 (공연 18:00 뒤, 맨 아래 자리)
		assertThat(addMemo(jieun, "shared", 1, "야식", "18:00", null, 3).status()).isEqualTo(201);
	}

	@Test
	@DisplayName("[타임라인 순서] 화면이 보낸 항목 ID 순서대로 시간 미정 항목의 자리값을 다시 매긴다 (끌어 옮기기)")
	void 타임라인순서_보낸순서대로_자리값을_다시매긴다() {
		long sensoji = memo(1, "센소지", "09:30", null);
		long tsukiji = memo(1, "츠키지 시장", "12:00", null);
		long tonkatsu = memo(1, "돈까스집", null, null);
		long gacha = memo(1, "가챠샵", null, null);
		assertThat(timeline("0", 1)).containsExactly("센소지", "츠키지 시장", "돈까스집", "가챠샵");

		// 가챠샵을 맨 위로, 돈까스집을 센소지와 츠키지 사이로
		TestApi.Response response = api.put(jieun.token(), trip + "/schedules/shared/days/1/order",
				TestApi.json("orderedIds", List.of(gacha, sensoji, tonkatsu, tsukiji), "movedId", tonkatsu));
		assertThat(response.status()).isEqualTo(200);

		assertThat(timeline("0", 1)).containsExactly("가챠샵", "센소지", "돈까스집", "츠키지 시장");
		assertThat(row(gacha).get("anchor_minute")).isEqualTo(-1);
		assertThat(row(gacha).get("sort_order")).isEqualTo(0);
		assertThat(row(tonkatsu).get("anchor_minute")).isEqualTo(570);
		assertThat(row(tonkatsu).get("sort_order")).isEqualTo(0);
		List<String> returned = new ArrayList<>();
		response.body().forEach(item -> returned.add(item.get("title").asString()));
		assertThat(returned).containsExactly("가챠샵", "센소지", "돈까스집", "츠키지 시장");
	}

	@Test
	@DisplayName("[타임라인 순서] 받은 순서에서 시간 항목끼리 start_time 순서가 아니면 거절한다. 항목이 빠지거나 남의 것이 섞여도 거절한다")
	void 타임라인순서_시간항목_순서가_틀리면_거절한다() {
		long sensoji = memo(1, "센소지", "09:30", null);
		long tsukiji = memo(1, "츠키지 시장", "12:00", null);
		long untimed = memo(1, "돈까스집", null, null);
		long otherDay = memo(2, "다른 날", null, null);
		String path = trip + "/schedules/shared/days/1/order";

		assertThat(code(api.put(jieun.token(), path,
				TestApi.json("orderedIds", List.of(tsukiji, sensoji, untimed), "movedId", untimed))))
				.isEqualTo("SCHEDULE_ORDER_INVALID");
		assertThat(code(api.put(jieun.token(), path,
				TestApi.json("orderedIds", List.of(sensoji, tsukiji), "movedId", untimed))))
				.isEqualTo("SCHEDULE_ORDER_INVALID");
		assertThat(code(api.put(jieun.token(), path,
				TestApi.json("orderedIds", List.of(sensoji, tsukiji, untimed, otherDay), "movedId", untimed))))
				.isEqualTo("SCHEDULE_ORDER_INVALID");
		assertThat(timeline("0", 1)).containsExactly("센소지", "츠키지 시장", "돈까스집");
	}

	@Test
	@DisplayName("[타임라인 순서] 시간 항목을 시간 미정으로 바꾸면 있던 자리에 남는다 (anchor=바꾸기 전 시작 분, sort_order=그 자리의 미정 항목들보다 앞)")
	void 타임라인순서_시간미정으로_바꾸면_있던자리에_남는다() {
		memo(1, "센소지", "09:30", null);
		long lunch = memo(1, "점심", "12:00", null);
		memo(1, "저녁", "18:00", null);
		// 점심(12:00) 바로 뒤에 놓인 미정 항목
		long after = addMemo(jieun, "shared", 1, "디저트", null, null, 2).body().get("id").asLong();
		assertThat(row(after).get("anchor_minute")).isEqualTo(720);

		assertThat(patch(jieun, lunch, time(null, null)).status()).isEqualTo(200);

		assertThat(row(lunch).get("start_time")).isNull();
		assertThat(row(lunch).get("anchor_minute")).isEqualTo(720);
		assertThat((Integer) row(lunch).get("sort_order")).isLessThan((Integer) row(after).get("sort_order"));
		// 맨 아래로 내려가지 않고 센소지와 저녁 사이, 디저트 앞에 남는다
		assertThat(timeline("0", 1)).containsExactly("센소지", "점심", "디저트", "저녁");
	}

	@Test
	@DisplayName("[타임라인 순서] 시간 미정 항목에 시간을 넣으면 anchor_minute=NULL, sort_order=0으로 비우고 시간순 자리로 간다")
	void 타임라인순서_시간을_넣으면_자리값을_비운다() {
		memo(1, "센소지", "09:30", null);
		memo(1, "저녁", "18:00", null);
		long untimed = addMemo(jieun, "shared", 1, "카페", null, null, 0).body().get("id").asLong();
		addMemo(jieun, "shared", 1, "산책", null, null, 1);
		assertThat(row(untimed).get("anchor_minute")).isEqualTo(-1);
		assertThat(timeline("0", 1)).containsExactly("카페", "산책", "센소지", "저녁");

		assertThat(patch(jieun, untimed, time("14:00", "15:00")).status()).isEqualTo(200);

		assertThat(row(untimed).get("anchor_minute")).isNull();
		assertThat(row(untimed).get("sort_order")).isEqualTo(0);
		assertThat(timeline("0", 1)).containsExactly("산책", "센소지", "카페", "저녁");
	}

	/* ───────── [Day 이동] ───────── */

	@Test
	@DisplayName("[Day 이동] 다른 Day로 옮기면 시간을 비우고(start_time, end_time=NULL) 겹침 검증 없이 옮겨 간 Day의 맨 아래 시간 미정으로 놓는다")
	void Day이동_시간을_비우고_맨아래에_놓는다() {
		long sensoji = memo(3, "센소지", "09:30", "11:00");
		memo(1, "같은 시간 일정", "09:30", "11:00");
		long bottom = memo(1, "미정 항목", null, null);

		TestApi.Response moved = api.post(jieun.token(), trip + "/schedule-items/" + sensoji + "/move",
				TestApi.json("dayNo", 1));
		assertThat(moved.status()).isEqualTo(200);
		assertThat(moved.body().get("dayNo").asInt()).isEqualTo(1);
		assertThat(moved.body().get("startTime").isNull()).isTrue();

		var row = row(sensoji);
		assertThat(row.get("day_no")).isEqualTo(1);
		assertThat(row.get("start_time")).isNull();
		assertThat(row.get("end_time")).isNull();
		assertThat(row.get("anchor_minute")).isNull();
		assertThat(row.get("sort_order")).isEqualTo((Integer) row(bottom).get("sort_order") + 1);
		assertThat(timeline("0", 1)).containsExactly("같은 시간 일정", "미정 항목", "센소지");
		assertThat(timeline("0", 3)).isEmpty();
		assertThat(code(api.post(jieun.token(), trip + "/schedule-items/" + sensoji + "/move",
				TestApi.json("dayNo", 6)))).isEqualTo("INVALID_DAY");
	}

	/* ───────── [하이라이트] ───────── */

	@Test
	@DisplayName("[하이라이트] 직접 추가/이동/시간 변경/내용 수정했을 때 last_change_type, last_changed_by_user_id, last_changed_at을 함께 갱신한다")
	void 하이라이트_직접_바꾼_항목만_갱신한다() {
		long item = memo(1, "센소지", null, null);
		assertThat(row(item).get("last_change_type")).isEqualTo("ADDED");
		assertThat(row(item).get("last_changed_by_user_id")).isEqualTo(jieun.id());
		assertThat(row(item).get("last_changed_at")).isNotNull();
		jdbc.update("update schedule_items set last_changed_at = '2020-01-01 00:00:00' where id = ?", item);

		// 시간 변경 -> TIME_CHANGED (민수가 바꾸면 변경자도 민수)
		assertThat(patch(minsu, item, time("10:00", null)).body().get("lastChangeType").asString())
				.isEqualTo("TIME_CHANGED");
		assertThat(row(item).get("last_changed_by_user_id")).isEqualTo(minsu.id());
		assertThat(String.valueOf(row(item).get("last_changed_at"))).doesNotStartWith("2020");

		// 메모·제목 수정 -> EDITED
		ObjectNode edit = TestApi.json("memo", "아침 일찍", "title", "센소지 구경");
		JsonNode edited = patch(jieun, item, edit).body();
		assertThat(edited.get("lastChangeType").asString()).isEqualTo("EDITED");
		assertThat(edited.get("memo").asString()).isEqualTo("아침 일찍");
		assertThat(edited.get("title").asString()).isEqualTo("센소지 구경");
		assertThat(edited.get("startTime").asString()).isEqualTo("10:00");
		assertThat(edited.get("lastChangedByUserId").asLong()).isEqualTo(jieun.id());

		// Day 이동 -> MOVED
		assertThat(api.post(minsu.token(), trip + "/schedule-items/" + item + "/move", TestApi.json("dayNo", 2))
				.body().get("lastChangeType").asString()).isEqualTo("MOVED");

		// 자리 옮기기 -> MOVED (끌어 옮긴 항목만)
		long other = memo(2, "다른 항목", null, null);
		jdbc.update("update schedule_items set last_changed_at = '2020-01-01 00:00:00', last_change_type = 'ADDED'");
		api.put(jieun.token(), trip + "/schedules/shared/days/2/order",
				TestApi.json("orderedIds", List.of(other, item), "movedId", other));
		assertThat(row(other).get("last_change_type")).isEqualTo("MOVED");
		assertThat(row(other).get("last_changed_by_user_id")).isEqualTo(jieun.id());
		assertThat(String.valueOf(row(other).get("last_changed_at"))).doesNotStartWith("2020");
	}

	@Test
	@DisplayName("[하이라이트] 다른 항목을 끼워 넣느라 자리값만 다시 매겨진 항목은 세 컬럼을 바꾸지 않는다")
	void 하이라이트_자리값만_바뀐_항목은_그대로다() {
		memo(1, "센소지", "09:30", null);
		long pushed = addMemo(minsu, "shared", 1, "가챠샵", null, null, 1).body().get("id").asLong();
		jdbc.update("update schedule_items set last_changed_at = '2020-01-01 00:00:00' where id = ?", pushed);

		// 지은이 가챠샵 앞에 끼워 넣는다 -> 가챠샵의 sort_order는 0에서 1로 밀린다
		addMemo(jieun, "shared", 1, "돈까스집", null, null, 1);

		var row = row(pushed);
		assertThat(row.get("sort_order")).isEqualTo(1);
		assertThat(row.get("last_change_type")).isEqualTo("ADDED");
		assertThat(row.get("last_changed_by_user_id")).isEqualTo(minsu.id());
		assertThat(String.valueOf(row.get("last_changed_at"))).startsWith("2020-01-01");

		// 순서 바꾸기에서도 끌어 옮긴 항목이 아니면 그대로다
		long third = addMemo(jieun, "shared", 1, "편의점", null, null, 3).body().get("id").asLong();
		List<Long> ids = jdbc.queryForList(
				"select id from schedule_items where day_no = 1 order by (start_time is null), anchor_minute, sort_order",
				Long.class);
		List<Long> reordered = new ArrayList<>(ids);
		reordered.remove(third);
		reordered.add(1, third);
		api.put(jieun.token(), trip + "/schedules/shared/days/1/order",
				TestApi.json("orderedIds", reordered, "movedId", third));
		assertThat(row(pushed).get("sort_order")).isEqualTo(2);
		assertThat(String.valueOf(row(pushed).get("last_changed_at"))).startsWith("2020-01-01");
		assertThat(row(pushed).get("last_change_type")).isEqualTo("ADDED");
	}

	/* ───────── [채팅] 시스템 메시지 ───────── */

	@Test
	@DisplayName("[채팅] 시스템 메시지는 공동 일정이 바뀔 때만 남긴다 (추가, 시간 변경, Day 이동, 제외)")
	void 채팅_공동일정이_바뀌면_시스템메시지를_남긴다() {
		long placeId = addPlace(jieun, tripId, "g-osaka", "오사카성");
		long item = addPlaceItem(jieun, "shared", 3, placeId, "13:00", "15:00").body().get("id").asLong();
		long checkin = memo(1, "숙소 체크인", null, null);
		patch(minsu, item, time("14:00", "15:00"));
		api.post(minsu.token(), trip + "/schedule-items/" + item + "/move", TestApi.json("dayNo", 2));
		api.delete(jieun.token(), trip + "/schedule-items/" + checkin);
		// 메모만 고친 것은 기록하지 않는다
		patch(jieun, item, TestApi.json("memo", "입장권 예매"));

		assertThat(chatTexts()).containsExactly(
				"지은님이 Day 3 13:00~15:00에 오사카성을 추가했습니다.",
				"지은님이 Day 1에 숙소 체크인을 추가했습니다.",
				"민수님이 Day 3 오사카성의 시간을 14:00~15:00(으)로 바꿨습니다.",
				"민수님이 오사카성을 Day 3에서 Day 2(으)로 옮겼습니다.",
				"지은님이 Day 1에서 숙소 체크인을 제외했습니다.");
		assertThat(jdbc.queryForObject(
				"select count(*) from chat_messages where message_type = 'SYSTEM' and sender_user_id is null",
				Integer.class)).isEqualTo(5);
	}

	@Test
	@DisplayName("[채팅] 개인 일정의 변경은 채팅에 남기지 않는다")
	void 채팅_개인일정_변경은_남기지_않는다() {
		String personal = String.valueOf(jieun.id());
		long item = addMemo(jieun, personal, 1, "혼자 쇼핑", "10:00", null, null).body().get("id").asLong();
		patch(jieun, item, time("11:00", null));
		api.post(jieun.token(), trip + "/schedule-items/" + item + "/move", TestApi.json("dayNo", 2));
		api.delete(jieun.token(), trip + "/schedule-items/" + item);

		assertThat(chatTexts()).isEmpty();
	}

	@Test
	@DisplayName("장소 카드를 삭제하면 일정에 배치된 항목도 함께 제거된다 (F2-6)")
	void placeDeleteRemovesItems() {
		long placeId = addPlace(jieun, tripId, "g-osaka", "오사카성");
		addPlaceItem(jieun, "shared", 3, placeId, null, null);
		addPlaceItem(minsu, String.valueOf(minsu.id()), 1, placeId, null, null);

		assertThat(api.delete(minsu.token(), trip + "/places/" + placeId).status()).isEqualTo(204);
		assertThat(api.get(jieun.token(), trip + "/schedule-items").body()).isEmpty();
	}
}
