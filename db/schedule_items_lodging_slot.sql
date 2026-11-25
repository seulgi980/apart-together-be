-- ============================================================
-- schedule_items: 숙소(LODGING) 카드는 한 일정에 2번까지 배치할 수 있다 (체크인 / 체크아웃)
-- places_english.sql 다음에 실행한다. 이미 운영 중인 DB에도 이 파일만 실행하면 된다.
--
--  * place_slot : 같은 장소를 한 일정에 몇 번째로 넣었는지. 1 또는 2.
--      - 숙소가 아닌 장소는 항상 1 -> 기존처럼 한 번만 배치된다.
--      - 숙소는 1, 2 중 비어 있는 번호를 서비스가 골라 넣는다.
--      - 메모 항목은 place_key가 NULL이라 유니크에 걸리지 않는다. (값은 기본값 1)
--  * 유니크를 (schedule_id, place_key) -> (schedule_id, place_key, place_slot)으로 바꾼다.
--    "몇 번까지 허용하는지"(숙소 2, 나머지 1)는 서비스가 정하고,
--    DB는 "같은 번호가 두 번 들어가는 것"만 막는다. (동시 요청 최후 방어선)
--  * 기존 행은 모두 place_slot = 1이 되므로 기존 데이터와 충돌하지 않는다.
-- ============================================================
ALTER TABLE schedule_items
  ADD COLUMN place_slot TINYINT NOT NULL DEFAULT 1
    COMMENT '같은 장소의 몇 번째 배치인지 (1, 숙소만 2까지). 메모 항목은 1' AFTER place_key,
  DROP INDEX uq_schedule_items_place,
  ADD UNIQUE KEY uq_schedule_items_place (schedule_id, place_key, place_slot),
  ADD CONSTRAINT ck_schedule_items_place_slot CHECK (place_slot IN (1, 2));
