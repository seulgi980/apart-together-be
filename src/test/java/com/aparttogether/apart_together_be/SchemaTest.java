package com.aparttogether.apart_together_be;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aparttogether.apart_together_be.support.IntegrationTest;

/** 1단계: db/schema.sql + db/constraints.sql + db/refresh_tokens.sql 이 그대로 적용되고 엔티티와 맞는지 */
class SchemaTest extends IntegrationTest {

	@Test
	@DisplayName("constraints.sql의 CHECK 13개(1부 9개 + 2부 4개)가 모두 적용된다")
	void allChecksApplied() {
		List<String> checks = jdbc.queryForList("""
				select constraint_name from information_schema.table_constraints
				where constraint_schema = database() and constraint_type = 'CHECK' order by constraint_name
				""", String.class);
		assertThat(checks).containsExactlyInAnyOrder(
				"ck_trips_dates", "ck_trip_members_pin", "ck_trip_members_state",
				"ck_places_lat", "ck_places_lng", "ck_places_geo",
				"ck_schedule_items_day", "ck_schedule_items_time", "ck_schedule_items_anchor",
				// 2부: 3823 오류 없이 적용됨
				"ck_schedules_owner", "ck_chat_messages_sender", "ck_places_dedupe", "ck_schedule_items_kind");
	}

	@Test
	@DisplayName("CHECK가 실제로 막는다: 종료일이 시작일보다 빠른 여행방은 저장되지 않는다")
	void checkRejectsBadRow() {
		jdbc.update("insert into users (login_id, password_hash, nickname) values ('a_user', 'x', '가나')");
		Long userId = jdbc.queryForObject("select id from users", Long.class);
		assertThatThrownBy(() -> jdbc.update(
				"insert into trips (owner_user_id, title, start_date, end_date, invite_code) values (?, 't', '2026-12-05', '2026-12-01', 'CODE')",
				userId)).hasMessageContaining("ck_trips_dates");
	}
}
