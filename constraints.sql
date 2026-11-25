-- ============================================================
-- 따로같이 제약조건 (MySQL 8.0.16 이상) — schema.sql v3 기준
-- schema.sql을 먼저 실행한 뒤 이 파일을 실행한다. (실제 DB에만 적용, ERD Cloud 가져오기에는 쓰지 않음)
--
-- 1부: 컬럼이 서로 독립적인 CHECK  -> 오류 없이 적용된다.
-- 2부: FK가 걸린 컬럼이 들어간 CHECK -> MySQL이 "외래키 참조 동작이 걸린 컬럼은 CHECK에 쓸 수 없다"는
--      오류(3823)를 내면, 그 항목만 서비스 코드 검증으로 대체한다.
--      (이 오류는 ON DELETE SET NULL, ON UPDATE CASCADE처럼 FK가 그 컬럼의 값을 바꾸는 경우에 난다.
--       2부의 컬럼은 그런 동작이 없어서 적용될 가능성이 높지만, 직접 실행해 확인한 것은 아니다.)
-- 3부: DB로 막을 수 없어서 서비스 코드에서 지켜야 하는 규칙 (주석)
-- ============================================================

-- ------------------------------------------------------------
-- 1부. 바로 적용 가능한 CHECK
-- ------------------------------------------------------------

-- 여행 종료일은 시작일보다 빠를 수 없다.
-- (최대 여행 일수 99일은 앱 설정값이라 DB에 박지 않는다. 3부 [여행 기간])
ALTER TABLE trips
  ADD CONSTRAINT ck_trips_dates CHECK (end_date >= start_date);

-- 핀 색 번호는 1 이상이다. (상한은 앱 설정값이라 DB에 박지 않는다)
-- ACTIVE 멤버는 핀 색이 있어야 하고 나간 시각이 없다.
-- ACTIVE가 아니면 핀 색은 비어 있어야 하고, 나간 시각이 있어야 한다.
ALTER TABLE trip_members
  ADD CONSTRAINT ck_trip_members_pin   CHECK (pin_color_no IS NULL OR pin_color_no >= 1),
  ADD CONSTRAINT ck_trip_members_state CHECK (
    (status = 'ACTIVE' AND left_at IS NULL AND pin_color_no IS NOT NULL)
    OR (status <> 'ACTIVE' AND left_at IS NOT NULL AND pin_color_no IS NULL)
  );

-- 위도와 경도는 범위 안에 있어야 하고, 둘 다 있거나 둘 다 없어야 한다.
ALTER TABLE places
  ADD CONSTRAINT ck_places_lat  CHECK (latitude  IS NULL OR latitude  BETWEEN -90  AND 90),
  ADD CONSTRAINT ck_places_lng  CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
  ADD CONSTRAINT ck_places_geo  CHECK ((latitude IS NULL) = (longitude IS NULL));

-- Day 번호는 1 이상이다.
-- 종료시간이 있으면 시작시간도 있어야 하고, 종료시간은 시작시간보다 늦어야 한다. (같은 날 안에서)
-- 자리값(anchor_minute)은 시간 미정 항목만 가진다. -1(맨 위) 또는 0~1439(앞 항목의 시작 시각, 분).
ALTER TABLE schedule_items
  ADD CONSTRAINT ck_schedule_items_day    CHECK (day_no >= 1),
  ADD CONSTRAINT ck_schedule_items_time   CHECK (
    end_time IS NULL OR (start_time IS NOT NULL AND end_time > start_time)
  ),
  ADD CONSTRAINT ck_schedule_items_anchor CHECK (
    anchor_minute IS NULL
    OR (start_time IS NULL AND anchor_minute BETWEEN -1 AND 1439)
  );

-- ------------------------------------------------------------
-- 2부. 오류(3823)가 날 수 있는 CHECK
--      하나씩 실행해 보고, 오류가 나는 항목은 건너뛰고 서비스 코드로 검증한다.
-- ------------------------------------------------------------

-- 공동 일정은 주인이 없고 owner_key가 0, 개인 일정은 주인이 있고 owner_key가 주인 ID와 같아야 한다.
ALTER TABLE schedules
  ADD CONSTRAINT ck_schedules_owner CHECK (
    (scope = 'SHARED' AND owner_user_id IS NULL AND owner_key = 0)
    OR (scope = 'PERSONAL' AND owner_user_id IS NOT NULL AND owner_key = owner_user_id)
  );

-- 사용자 메시지는 보낸 사람이 있어야 한다. (시스템 메시지만 NULL 허용)
ALTER TABLE chat_messages
  ADD CONSTRAINT ck_chat_messages_sender CHECK (
    message_type = 'SYSTEM' OR sender_user_id IS NOT NULL
  );

-- 공동 카드는 dedupe_owner_id가 0, 나만 카드는 등록자 ID와 같아야 한다.
-- (나만 카드를 없애면서 shared_cards_redesign.sql이 이 CHECK와 두 컬럼을 지운다)
ALTER TABLE places
  ADD CONSTRAINT ck_places_dedupe CHECK (
    (visibility = 'SHARED'  AND dedupe_owner_id = 0)
    OR (visibility = 'PRIVATE' AND dedupe_owner_id = created_by_user_id)
  );

-- 장소 항목은 장소 카드와 place_key가 있고 제목이 없다.
-- 메모 항목은 장소 카드와 place_key가 없고 제목이 있다.
-- (shared_cards_redesign.sql이 place_key 컬럼을 지우면서 이 CHECK를 place_key 없이 다시 만든다)
ALTER TABLE schedule_items
  ADD CONSTRAINT ck_schedule_items_kind CHECK (
    (place_id IS NOT NULL AND place_key IS NOT NULL AND title IS NULL)
    OR (place_id IS NULL AND place_key IS NULL AND title IS NOT NULL)
  );

-- ------------------------------------------------------------
-- 3부. 서비스 코드에서 지켜야 하는 규칙 (DB 제약으로 표현할 수 없음)
-- ------------------------------------------------------------
-- [권한]
--  * 모든 조회/수정 API는 먼저 요청자가 해당 여행방의 ACTIVE 멤버인지 확인한다. (F1-5)
--    (FK가 users를 가리키므로 DB만으로는 "이 방의 참여자인지"를 알 수 없다)
--  * 장소 카드는 모두 방의 멤버 모두가 함께 쓴다. 누가 등록했든 멤버라면 조회/수정/삭제/투표할 수 있다.
--    (나만 카드는 shared_cards_redesign.sql에서 없앴다)
--  * 다른 멤버의 개인 일정은 읽기 전용이다. (F4-2)
--
-- [여행 기간]
--  * 여행 기간은 최대 일수 설정값(현재 99일, Day 1 ~ Day 99)을 넘을 수 없다. 방을 만들 때와 기간을 고칠 때 확인한다.
--  * 여행 기간을 줄이면 범위를 벗어난 day_no의 일정 항목을 확인 후 삭제한다. (F1-8)
--
-- [일정 검증] (일정 행을 SELECT ... FOR UPDATE로 잠근 트랜잭션 안에서)
--  * 시간 겹침 검증: 같은 일정, 같은 Day 안에서 시간 범위가 겹치면 거절한다. (F4-19)
--    - 종료시간이 없으면 시작 시각 한 점으로 본다.
--    - 맞닿는 경우(13:00~14:00과 14:00~15:00)는 겹침이 아니다.
--    - start_time이 NULL인 항목은 검사에서 제외하고, 시간을 입력하는 시점에 검사한다.
--  * 같은 장소를 한 일정에 몇 번이든 넣을 수 있다. 중복 배치 검사는 없다. (F4-20)
--    (예전의 uq_schedule_items_place, place_key, place_slot은 shared_cards_redesign.sql에서 지웠다)
--  * 방의 카드는 공동 일정, 개인 일정 어디에든 넣을 수 있다. 다른 방의 카드는 넣을 수 없다.
--
-- [타임라인 순서] (schedule_items.anchor_minute, sort_order)
--  * 화면은 "이 Day의 항목을 이 순서로"라고 항목 ID 목록을 보낸다. (틈에 끼워 넣기, 시간 미정 항목 옮기기)
--    서비스는 그 순서를 위에서부터 훑으며 시간 미정 항목의 자리값을 다시 매긴다.
--      - anchor_minute = 바로 앞 시간 항목의 시작 시각(분). 앞에 시간 항목이 없으면 -1.
--      - sort_order    = 같은 자리 안에서 0, 1, 2 ...
--    받은 순서에서 시간 항목끼리는 start_time 순서여야 한다. 아니면 거절한다.
--  * 틈에 새 항목을 넣을 때 시간을 정했다면, 그 시간은 앞뒤 시간 항목 사이에 들어가야 한다.
--    (앞 항목이 끝난 뒤, 종료시간이 없는 앞 항목이면 그 시작 시각보다 뒤. 뒤 항목의 시작 전.)
--    화면이 먼저 막지만 서버도 겹침 검증으로 다시 확인한다.
--  * 시간 항목을 시간 미정으로 바꾸면 있던 자리에 남긴다:
--      anchor_minute = 바꾸기 전 start_time(분), sort_order = 그 자리의 미정 항목들보다 앞.
--  * 시간 미정 항목에 시간을 넣으면 anchor_minute = NULL, sort_order = 0으로 비운다. (시간순으로 자리가 정해진다)
--  * 장소 풀의 [+] 버튼으로 넣은 항목은 그 Day의 맨 아래 시간 미정으로 넣는다.
--      anchor_minute = NULL, sort_order = (맨 아래 미정 항목들의 최대값 + 1)
--
-- [Day 이동]
--  * 항목을 다른 Day로 옮기면 시간을 비운다. (start_time, end_time = NULL)
--    옮겨 간 Day의 일정과 시간이 겹칠 수 있기 때문이다. 그래서 Day 이동에는 겹침 검증이 없다.
--  * 옮겨 간 Day의 맨 아래 시간 미정으로 놓는다. (anchor_minute = NULL, sort_order = 최대값 + 1)
--
-- [하이라이트] (F4-17)
--  * 사용자가 항목을 직접 추가/이동/시간 변경/내용 수정했을 때만
--    last_change_type, last_changed_by_user_id, last_changed_at을 함께 갱신한다.
--      ADDED: 일정에 추가 / MOVED: Day 이동, 자리 옮기기 / TIME_CHANGED: 시간 변경 / EDITED: 메모·제목 수정
--  * 다른 항목을 끼워 넣느라 자리값(anchor_minute, sort_order)만 다시 매겨진 항목은 이 세 컬럼을 바꾸지 않는다.
--  * 화면은 last_changed_at이 설정값(현재 30초) 안인 항목을 강조한다. 내가 바꾼 항목도 내 닉네임으로 강조한다.
--
-- [일정 생성]
--  * 공동 일정(scope=SHARED, owner_key=0)은 여행방을 만드는 트랜잭션 안에서 만든다.
--    (uq_schedules_trip_owner 유니크가 중복을 막는다)
--  * 개인 일정(scope=PERSONAL, owner_key=주인 ID)은 멤버가 방에 참여할 때 만든다. 이미 있으면 다시 만들지 않는다.
--
-- [투표]
--  * 모든 카드에 투표할 수 있다. 한 사람은 카드마다 LIKE/PASS 중 하나만 (uq_place_votes_place_user).
--  * 공동 일정의 장소 풀에는 패스된 카드가 나오지 않고, 개인 일정의 장소 풀에는 모든 카드가 나온다. (화면 규칙)
--
-- [채팅]
--  * 시스템 메시지는 공동 일정이 바뀔 때와 장소 카드를 등록할 때 남긴다. 개인 일정의 변경은 채팅에 남기지 않는다.
--    카드 등록 알림은 shared_place_id에 그 카드를 넣는다. (message_type = SYSTEM이라 공유 메시지와 구분된다)
--  * 안 읽은 수는 남이 보낸 말풍선 메시지(message_type = USER: 일반 메시지, 장소 카드 공유)만 센다. 시스템 알림은 세지 않는다.
--  * 장소 카드 공유(shared_place_id)는 같은 여행방의 카드만 허용한다.
--
-- [멤버 나가기/내보내기] (F1-11)
--  * 한 트랜잭션 안에서 처리한다.
--    1) trip_members: status를 LEFT/KICKED로, pin_color_no를 NULL로, left_at을 현재 시각으로
--    2) 그 사용자가 이 여행방에서 한 place_votes 삭제 (이 방의 카드에 대한 투표만)
--    3) 그 사용자의 개인 일정(schedules, PERSONAL)과 그 안의 항목 삭제
--  * 장소 카드, 참고자료, 채팅은 남긴다. 화면에는 "탈퇴한 멤버"로 표시한다.
--
-- [재참여]
--  * 나갔던(LEFT) 사람이 초대코드로 다시 들어오면 trip_members에 새 행을 만들지 않는다.
--    ((trip_id, user_id) 유니크 때문에 만들 수 없다)
--    기존 행을 status=ACTIVE, left_at=NULL로 되살리고 핀 색을 새로 배정한 뒤, 개인 일정을 새로 만든다.
--    joined_at은 다시 들어온 시각으로 바꾼다.
--  * 내보내진(KICKED) 사람은 다시 들어올 수 없다. 초대코드로 참여를 시도하면 거절한다.
--    (참여 처리에서 trip_members에 status=KICKED 행이 있는지 먼저 확인한다)
--  * 다시 들어오면 예전에 남긴 장소 카드, 참고자료, 채팅은 따로 손대지 않아도 다시 닉네임으로 보인다.
--    이 기록들은 user_id를 그대로 갖고 있고, "탈퇴한 멤버" 표시는 저장된 값이 아니라
--    조회할 때 trip_members.status가 ACTIVE가 아니면 그렇게 보여 주는 것이기 때문이다.
--  * 나갈 때 지운 것(투표, 개인 일정)은 되살아나지 않는다. 새로 시작한다.
--  * 안 읽은 채팅 수는 last_read_chat_id를 다시 들어온 시점의 마지막 메시지로 맞춰 0부터 센다.
--
-- [인원 제한]
--  * ACTIVE 멤버 수가 최대 인원 설정값(현재 10)을 넘지 않게 참여 시 확인한다. 설정값은 코드에 박지 않는다.
--
-- [핀 색]
--  * 방 안에서 겹치지 않게 자동 배정한다. 유니크(uq_trip_members_trip_pin)가 중복을 막으므로,
--    충돌하면 다른 번호로 다시 시도한다.
