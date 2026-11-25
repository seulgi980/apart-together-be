-- ============================================================
-- 장소 카드 재설계 (schedule_items_lodging_slot.sql 다음에 실행한다)
-- 이미 운영 중인 DB에도 이 파일만 실행하면 된다. (실행 전에 꼭 백업)
--
-- [1부] 나만 카드 없애기: 장소 카드는 전부 공동이고, 방마다 같은 장소(place_key)는 1장이다.
--   운영 데이터 합치기 규칙
--    * 같은 방 + 같은 place_key 카드들 중 남길 카드 1장을 고른다.
--        - 공동 카드가 있으면 그 공동 카드
--        - 나만 카드만 있으면 가장 먼저 만든 카드 (id가 가장 작은 것)
--    * 나머지 카드의 메모는 남길 카드의 메모 뒤에 " / "로 이어 붙인다. (300자까지)
--    * 나머지 카드로 넣은 일정 항목, 참고자료, 채팅 공유는 남길 카드로 옮긴다.
--    * 나머지 카드의 투표는 지운다. (나만 카드에는 원래 투표가 없다)
--    * 나머지 카드를 지운다.
--   그다음 visibility, dedupe_owner_id 컬럼을 지우고 (trip_id, place_key) 유니크를 건다.
-- ============================================================

-- 1-1. 지울 카드 -> 남길 카드 대응표
CREATE TEMPORARY TABLE tmp_place_merge (
  place_id  BIGINT NOT NULL PRIMARY KEY,
  keeper_id BIGINT NOT NULL
);

INSERT INTO tmp_place_merge (place_id, keeper_id)
SELECT p.id, k.keeper_id
FROM places p
JOIN (
  SELECT trip_id, place_key,
         COALESCE(MIN(CASE WHEN visibility = 'SHARED' THEN id END), MIN(id)) AS keeper_id
  FROM places
  GROUP BY trip_id, place_key
) k ON k.trip_id = p.trip_id AND k.place_key = p.place_key
WHERE p.id <> k.keeper_id;

-- 1-2. 지울 카드의 메모를 남길 카드 기준으로 모아 둔다 (남길 카드와 같은 메모는 빼고)
CREATE TEMPORARY TABLE tmp_place_memo (
  keeper_id BIGINT NOT NULL PRIMARY KEY,
  memos     TEXT   NOT NULL
);

INSERT INTO tmp_place_memo (keeper_id, memos)
SELECT m.keeper_id, GROUP_CONCAT(p.memo ORDER BY p.id SEPARATOR ' / ')
FROM tmp_place_merge m
JOIN places p ON p.id = m.place_id
JOIN places k ON k.id = m.keeper_id
WHERE p.memo IS NOT NULL AND p.memo <> '' AND p.memo <> COALESCE(k.memo, '')
GROUP BY m.keeper_id;

UPDATE places k
JOIN tmp_place_memo t ON t.keeper_id = k.id
SET k.memo = LEFT(
  CASE WHEN k.memo IS NULL OR k.memo = '' THEN t.memos ELSE CONCAT(k.memo, ' / ', t.memos) END,
  300);

-- 1-3. 지울 카드에 걸린 것들을 남길 카드로 옮긴다
UPDATE schedule_items si
JOIN tmp_place_merge m ON m.place_id = si.place_id
SET si.place_id = m.keeper_id;

UPDATE place_references r
JOIN tmp_place_merge m ON m.place_id = r.place_id
SET r.place_id = m.keeper_id;

UPDATE chat_messages c
JOIN tmp_place_merge m ON m.place_id = c.shared_place_id
SET c.shared_place_id = m.keeper_id;

DELETE v FROM place_votes v
JOIN tmp_place_merge m ON m.place_id = v.place_id;

-- 1-4. 지울 카드 삭제
DELETE p FROM places p
JOIN tmp_place_merge m ON m.place_id = p.id;

DROP TEMPORARY TABLE tmp_place_memo;
DROP TEMPORARY TABLE tmp_place_merge;

-- 1-5. 구조 바꾸기
--   새 유니크를 먼저 걸고 옛 인덱스를 지운다. (trip_id FK가 쓸 인덱스가 항상 남아 있게)
ALTER TABLE places
  ADD UNIQUE KEY uq_places_trip_place (trip_id, place_key);

ALTER TABLE places
  DROP CHECK ck_places_dedupe,
  DROP INDEX uq_places_dedupe,
  DROP INDEX idx_places_trip_visibility,
  DROP COLUMN visibility,
  DROP COLUMN dedupe_owner_id;

-- ============================================================
-- [2부] 일정 배치 중복 허용: 같은 장소를 한 일정에 몇 번이든 넣을 수 있다. (시간 겹침 검사만 남는다)
--   * 같은 장소 유니크(uq_schedule_items_place)와 place_slot(숙소 2번), place_key(복사본)를 지운다.
--     카드가 방마다 1장이 되어서 일정 항목에 place_key를 따로 둘 이유도 없어졌다.
--   * ck_schedule_items_kind는 place_key를 빼고 다시 만든다.
-- ============================================================
ALTER TABLE schedule_items
  DROP CHECK ck_schedule_items_place_slot,
  DROP CHECK ck_schedule_items_kind;

ALTER TABLE schedule_items
  DROP INDEX uq_schedule_items_place,
  DROP COLUMN place_slot,
  DROP COLUMN place_key;

-- 장소 항목은 장소 카드가 있고 제목이 없다. 메모 항목은 장소 카드가 없고 제목이 있다.
ALTER TABLE schedule_items
  ADD CONSTRAINT ck_schedule_items_kind CHECK (
    (place_id IS NOT NULL AND title IS NULL)
    OR (place_id IS NULL AND title IS NOT NULL)
  );

-- ============================================================
-- [3부] 영어 지역명: 카드에 "영어 (한국어)"로 지역명을 보여주기 위해 영어 지역명을 따로 둔다.
--   * 장소 등록 때 구글에서 영어로 받은 짧은 지역명. 한국어 값과 같거나 받지 못하면 NULL.
--   * 이미 등록된 카드는 NULL이다. (화면은 area만 보여준다)
-- ============================================================
ALTER TABLE places
  ADD COLUMN area_en VARCHAR(50) NULL COMMENT '영어 지역명 (없으면 NULL)' AFTER area;
