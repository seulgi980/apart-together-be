-- ============================================================
-- 따로같이 DB 스키마 v3 (MySQL 8 기준)
-- ERD Cloud 캡처(9개 테이블) 기준
--
-- 이 파일: 테이블, PK, UNIQUE, FK, 인덱스 (ERD Cloud 가져오기용)
-- constraints.sql: CHECK 제약 (실제 DB에만 적용)
--
-- 설계 메모
--  * 카드 취향 상태(좋아요/패스/의견갈림/미평가)는 저장하지 않고 투표로 계산한다.
--  * users를 가리키는 FK에는 ON DELETE 동작을 걸지 않는다. (회원 삭제 기능이 없고, 삭제가 콘텐츠를 지우면 안 됨)
--    여행방(trips)을 가리키는 FK만 CASCADE로 걸어 방 삭제 시 안의 데이터가 함께 지워진다.
--  * 멤버가 나가도 trip_members 행은 남기고 status만 바꾼다. (탈퇴한 멤버로 표시)
--  * 최대 인원, 최대 여행 일수, 패스 기준, 하이라이트 시간, 핀 팔레트 개수는 DB가 아니라 앱 설정값으로 관리한다.
--
-- v2 -> v3 변경 (프론트 화면 개편 반영)
--  * places: area(짧은 지역명) 추가
--  * schedules: owner_key 추가. 공동 일정 중복도 DB 유니크로 막는다.
--  * schedule_items:
--     - title 추가 (장소 없는 메모 항목의 제목)
--     - anchor_minute, sort_order 추가 (시간 미정 항목이 타임라인 안에서 놓인 자리)
--     - last_changed_at 추가 (하이라이트 기준 시각. updated_at으로 판단하지 않는다)
--     - last_change_type 값 변경: UPDATED -> TIME_CHANGED, EDITED
--
-- 이 파일 이후의 변경 (운영 DB가 이미 있어서 ALTER 파일로 쌓는다. 순서대로 실행)
--  * constraints.sql            : CHECK 제약
--  * refresh_tokens.sql         : refresh token 테이블
--  * places_english.sql         : places.name_en, address_en
--  * schedule_items_lodging_slot.sql : schedule_items.place_slot (숙소 2번 배치. 다음 파일에서 다시 지움)
--  * shared_cards_redesign.sql  : 나만 카드 없앰(places.visibility, dedupe_owner_id 삭제, 같은 장소는 방마다 1장),
--                                 일정 중복 배치 허용(schedule_items.place_key, place_slot, uq_schedule_items_place 삭제)
--  아래 테이블 설명 중 나만 카드(PRIVATE), dedupe_owner_id, schedule_items.place_key 이야기는 v3 당시 기준이다.
-- ============================================================

-- ------------------------------------------------------------
-- 1. 회원 (F1)
-- ------------------------------------------------------------
CREATE TABLE users (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '회원 ID',
  login_id          VARCHAR(50)  NOT NULL COMMENT '로그인 아이디',
  password_hash     VARCHAR(255) NOT NULL COMMENT '비밀번호(해시)',
  nickname          VARCHAR(50)  NOT NULL COMMENT '닉네임',
  profile_image_url VARCHAR(500) NULL     COMMENT '프로필 사진 URL (없으면 기본 이미지)',
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '가입일시',
  updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '마지막 변경일시',
  PRIMARY KEY (id),
  UNIQUE KEY uq_users_login_id (login_id),
  UNIQUE KEY uq_users_nickname (nickname)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='회원';

-- ------------------------------------------------------------
-- 2. 여행방 (F1-4, F1-8, F1-10)
-- ------------------------------------------------------------
CREATE TABLE trips (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '여행방 ID',
  owner_user_id BIGINT       NOT NULL COMMENT '방주 회원 ID',
  title         VARCHAR(100) NOT NULL COMMENT '방 이름',
  start_date    DATE         NOT NULL COMMENT '여행 시작일',
  end_date      DATE         NOT NULL COMMENT '여행 종료일',
  invite_code   VARCHAR(32)  NOT NULL COMMENT '방 초대코드 (재발급 가능)',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '추가일시',
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '마지막 변경일시',
  PRIMARY KEY (id),
  UNIQUE KEY uq_trips_invite_code (invite_code),
  KEY idx_trips_owner (owner_user_id),
  CONSTRAINT fk_trips_owner FOREIGN KEY (owner_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='여행방';

-- ------------------------------------------------------------
-- 3. 여행방 멤버 (F1-2, F1-5, F1-11, F6-4)
--    나가거나 내보내져도 행은 유지하고 status만 변경한다.
--    LEFT/KICKED가 되면 pin_color_no는 NULL로 비워서 다른 멤버가 쓸 수 있게 한다.
--    (trip_id, user_id)가 유니크라서, 나갔던 사람이 다시 참여하면 새 행을 만들지 않고
--    기존 행을 ACTIVE로 되살린다. (constraints.sql 3부 [재참여])
--    last_read_chat_id는 단순 위치 값이라 FK를 걸지 않는다.
-- ------------------------------------------------------------
CREATE TABLE trip_members (
  id                BIGINT NOT NULL AUTO_INCREMENT COMMENT '여행방 멤버 ID',
  trip_id           BIGINT NOT NULL COMMENT '여행방 ID',
  user_id           BIGINT NOT NULL COMMENT '회원 ID',
  status            ENUM('ACTIVE','LEFT','KICKED') NOT NULL DEFAULT 'ACTIVE' COMMENT '상태: ACTIVE, LEFT(나감), KICKED(내보내짐)',
  pin_color_no      TINYINT  NULL COMMENT '핀 색 번호 (방 안에서 중복 불가, 나가면 NULL)',
  last_read_chat_id BIGINT   NULL COMMENT '마지막으로 읽은 채팅 메시지 ID (안 읽은 개수 계산용)',
  joined_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '참여일시',
  left_at           DATETIME NULL COMMENT '나간/내보내진 일시',
  PRIMARY KEY (id),
  UNIQUE KEY uq_trip_members_trip_user (trip_id, user_id),
  UNIQUE KEY uq_trip_members_trip_pin (trip_id, pin_color_no),
  KEY idx_trip_members_user (user_id),
  CONSTRAINT fk_trip_members_trip FOREIGN KEY (trip_id) REFERENCES trips (id) ON DELETE CASCADE,
  CONSTRAINT fk_trip_members_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='여행방 멤버';

-- ------------------------------------------------------------
-- 4. 장소 카드 (F2)
--    place_key        : 같은 장소를 판단하는 기준 값 (구글맵 링크에서 뽑은 장소 식별 값)
--    dedupe_owner_id  : 중복 검사용. 공동 카드는 0, 나만 카드는 소유자(등록자) 회원 ID
--
--    (trip_id, place_key, dedupe_owner_id) 유니크로 아래가 동시에 보장된다.
--     - 공동 카드끼리는 같은 장소 중복 불가 (등록자가 달라도)
--     - 같은 장소의 공동 카드와 나만 카드는 공존 가능
--     - 서로 다른 사람이 같은 장소를 각자 나만 카드로 가질 수 있음
--     - 나만 카드로 올린 같은 장소를 같은 사람이 또 올릴 수 없음
--     - "공동으로 올리기"로 visibility를 SHARED, dedupe_owner_id를 0으로 바꿀 때
--       이미 공동 카드가 있으면 유니크 오류로 막힌다. (F2-10)
-- ------------------------------------------------------------
CREATE TABLE places (
  id                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '장소카드 ID',
  trip_id            BIGINT        NOT NULL COMMENT '여행방 ID',
  created_by_user_id BIGINT        NOT NULL COMMENT '등록한 회원 ID (나만 카드의 소유자)',
  visibility         ENUM('SHARED','PRIVATE') NOT NULL DEFAULT 'SHARED' COMMENT '공개범위: SHARED(공동), PRIVATE(나만)',
  name               VARCHAR(200)  NOT NULL COMMENT '장소명',
  address            VARCHAR(300)  NULL     COMMENT '주소',
  area               VARCHAR(50)   NULL     COMMENT '카드에 보이는 짧은 지역명 (예: 아사쿠사). 추출 실패 시 NULL',
  latitude           DECIMAL(9,6)  NULL     COMMENT '위도 (자동 추출 실패 시 NULL. 좌표가 없으면 지도에 표시하지 않는다)',
  longitude          DECIMAL(9,6)  NULL     COMMENT '경도',
  google_maps_url    VARCHAR(2048) NOT NULL COMMENT '구글맵 링크',
  place_key          VARCHAR(200)  NOT NULL COMMENT '장소 중복 판단키',
  dedupe_owner_id    BIGINT        NOT NULL DEFAULT 0 COMMENT '중복검사 범위: 0=공동 카드, 그 외=나만 카드 소유자 회원 ID',
  category           ENUM('RESTAURANT','CAFE','SIGHTSEEING','LODGING','SHOPPING','ETC') NOT NULL COMMENT '카테고리',
  memo               VARCHAR(300)  NULL     COMMENT '메모',
  created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '추가일시',
  updated_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '마지막 변경일시',
  PRIMARY KEY (id),
  UNIQUE KEY uq_places_dedupe (trip_id, place_key, dedupe_owner_id),
  KEY idx_places_trip_visibility (trip_id, visibility, created_by_user_id),
  KEY idx_places_creator (created_by_user_id),
  CONSTRAINT fk_places_trip    FOREIGN KEY (trip_id) REFERENCES trips (id) ON DELETE CASCADE,
  CONSTRAINT fk_places_creator FOREIGN KEY (created_by_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='장소카드';

-- ------------------------------------------------------------
-- 5. 참고자료 (F2-4, F2-5)
-- ------------------------------------------------------------
CREATE TABLE place_references (
  id                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '참고자료 ID',
  place_id          BIGINT        NOT NULL COMMENT '장소카드 ID',
  added_by_user_id  BIGINT        NOT NULL COMMENT '추가한 회원 ID',
  url               VARCHAR(2048) NOT NULL COMMENT '링크주소',
  title             VARCHAR(200)  NOT NULL COMMENT '제목 (자동추출 또는 직접 입력)',
  memo              VARCHAR(300)  NULL     COMMENT '메모',
  created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '추가일시',
  updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '마지막 변경일시',
  PRIMARY KEY (id),
  KEY idx_place_references_place (place_id),
  KEY idx_place_references_user (added_by_user_id),
  CONSTRAINT fk_place_references_place FOREIGN KEY (place_id) REFERENCES places (id) ON DELETE CASCADE,
  CONSTRAINT fk_place_references_user  FOREIGN KEY (added_by_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='참고자료';

-- ------------------------------------------------------------
-- 6. 취향 투표 (F3)
--    공동 카드만 투표한다. (나만 카드는 투표 없음)
--    멤버가 나가면 해당 멤버의 투표 행 자체를 삭제한다. (F1-11)
-- ------------------------------------------------------------
CREATE TABLE place_votes (
  id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '투표 ID',
  place_id    BIGINT   NOT NULL COMMENT '장소카드 ID',
  user_id     BIGINT   NOT NULL COMMENT '투표한 회원 ID',
  vote_type   ENUM('LIKE','PASS') NOT NULL COMMENT '투표타입: LIKE(좋아요), PASS(패스)',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '투표일시',
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '마지막 변경일시',
  PRIMARY KEY (id),
  UNIQUE KEY uq_place_votes_place_user (place_id, user_id),
  KEY idx_place_votes_user (user_id),
  CONSTRAINT fk_place_votes_place FOREIGN KEY (place_id) REFERENCES places (id) ON DELETE CASCADE,
  CONSTRAINT fk_place_votes_user  FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='취향투표';

-- ------------------------------------------------------------
-- 7. 일정 (F4-1, F7)
--    여행당 공동 일정 1개 + 멤버당 개인 일정 1개
--    owner_key : 중복 검사용. 공동 일정은 0, 개인 일정은 주인 회원 ID (places.dedupe_owner_id와 같은 방식)
--      owner_user_id는 공동 일정에서 NULL이라 유니크가 걸리지 않으므로,
--      (trip_id, owner_key) 유니크로 공동 일정 1개와 멤버당 개인 일정 1개를 DB가 함께 보장한다.
--    SHARED(공동)와 PERSONAL(개인) 단어가 다른 이유:
--      장소 카드의 PRIVATE는 등록자만 볼 수 있지만, PERSONAL 일정은 다른 멤버가 읽기 전용으로 볼 수 있다.
--    이 행은 일정 변경 시 동시 요청을 직렬화하는 잠금 대상(SELECT ... FOR UPDATE)으로도 쓴다.
-- ------------------------------------------------------------
CREATE TABLE schedules (
  id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '일정 ID',
  trip_id        BIGINT       NOT NULL COMMENT '여행방 ID',
  scope          ENUM('SHARED','PERSONAL') NOT NULL COMMENT '구분: SHARED(공동), PERSONAL(개인)',
  owner_user_id  BIGINT       NULL     COMMENT '개인 일정의 주인 회원 ID (공동은 NULL)',
  owner_key      BIGINT       NOT NULL DEFAULT 0 COMMENT '중복검사 범위: 0=공동 일정, 그 외=개인 일정 주인 회원 ID',
  memo           VARCHAR(300) NULL     COMMENT '메모 (현재 화면에서는 쓰지 않음)',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',
  PRIMARY KEY (id),
  UNIQUE KEY uq_schedules_trip_owner (trip_id, owner_key),
  KEY idx_schedules_owner (owner_user_id),
  CONSTRAINT fk_schedules_trip  FOREIGN KEY (trip_id) REFERENCES trips (id) ON DELETE CASCADE,
  CONSTRAINT fk_schedules_owner FOREIGN KEY (owner_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='일정';

-- ------------------------------------------------------------
-- 8. 일정 항목 (F4)
--    place_id가 NULL이면 장소 없는 메모 항목이다.
--    title : 메모 항목의 제목 ("숙소 체크인" 등). 장소 항목은 NULL이고 장소명을 쓴다.
--    memo  : 항목에 붙인 메모. 장소 항목과 메모 항목 모두 쓸 수 있다.
--    place_key : places.place_key를 복사해 둔 값. (메모 항목은 NULL)
--      -> (schedule_id, place_key) 유니크로 "같은 장소는 한 일정에 한 번만"을 DB가 막는다.
--         카드가 달라도(공동/나만) 같은 구글맵 장소면 중복이다.
--    start_time/end_time : 현지 시각 그대로 저장한다. (시간대 변환 없음, 1분 단위)
--      start_time이 NULL이면 "시간 미정"이다.
--
--    [타임라인 순서] 시간 항목과 시간 미정 항목은 한 줄에 섞여 있다. ("시간 미정" 구역이 따로 없다)
--      09:30 센소지 / --:-- 돈까스집 / --:-- 가챠샵 / 12:00 츠키지 시장
--      - 시간 항목은 start_time 순서.
--      - 시간 미정 항목은 자기가 놓인 자리를 기억한다.
--          anchor_minute : 바로 앞 시간 항목의 시작 시각(자정부터의 분, 0~1439).
--                          -1이면 맨 위(첫 시간 항목보다 앞), NULL이면 맨 아래.
--          sort_order    : 같은 자리(같은 anchor_minute)에 놓인 미정 항목끼리의 순서.
--      - 시간 항목은 anchor_minute이 NULL, sort_order는 0이다.
--      조회 정렬:
--        ORDER BY
--          CASE WHEN start_time IS NOT NULL THEN HOUR(start_time) * 60 + MINUTE(start_time)
--               WHEN anchor_minute IS NULL  THEN 1440
--               ELSE anchor_minute END,
--          (start_time IS NULL),   -- 같은 시각이면 시간 항목이 먼저
--          sort_order,
--          id
--      앞 시간 항목의 시간이 바뀌거나 삭제돼도 미정 항목은 anchor_minute 기준으로 그 근처에 남는다.
--
--    시간 겹침 검증은 DB 제약으로 못 막으므로, 일정 행 잠금 후 서비스에서 검사한다.
--
--    [하이라이트] last_change_type, last_changed_by_user_id, last_changed_at (F4-17)
--      "지은 · 방금 추가됨" 표시의 문구, 변경자, 기준 시각이다. 30초 값은 설정값으로 한다.
--      기준 시각을 updated_at으로 쓰지 않는 이유:
--        틈에 항목 하나를 끼우면 주변 시간 미정 항목의 anchor_minute/sort_order도 같이 갱신되어
--        updated_at이 바뀐다. 그 항목들까지 "방금 변경됨"으로 뜨면 안 되므로,
--        사용자가 그 항목을 직접 바꿨을 때만 서비스가 last_changed_at을 넣는다.
-- ------------------------------------------------------------
CREATE TABLE schedule_items (
  id                       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '일정항목 ID',
  schedule_id              BIGINT       NOT NULL COMMENT '일정 ID',
  day_no                   INT          NOT NULL COMMENT 'Day 번호 (1부터)',
  place_id                 BIGINT       NULL     COMMENT '장소카드 ID (메모 항목은 NULL)',
  place_key                VARCHAR(200) NULL     COMMENT '장소카드 중복 판단키 복사본 (메모 항목은 NULL)',
  title                    VARCHAR(30)  NULL     COMMENT '메모 항목의 제목 (장소 항목은 NULL)',
  memo                     VARCHAR(300) NULL     COMMENT '항목에 붙인 메모',
  start_time               TIME         NULL     COMMENT '시작시간 (NULL이면 시간 미정)',
  end_time                 TIME         NULL     COMMENT '종료시간 (선택)',
  anchor_minute            SMALLINT     NULL     COMMENT '시간 미정 항목의 자리: 바로 앞 시간 항목의 시작 시각(분). -1=맨 위, NULL=맨 아래. 시간 항목은 NULL',
  sort_order               INT          NOT NULL DEFAULT 0 COMMENT '같은 자리에 놓인 시간 미정 항목끼리의 순서',
  last_change_type         ENUM('ADDED','MOVED','TIME_CHANGED','EDITED') NOT NULL COMMENT '마지막 변경 종류: ADDED(추가), MOVED(이동), TIME_CHANGED(시간 변경), EDITED(메모 등 내용 수정)',
  last_changed_by_user_id  BIGINT       NOT NULL COMMENT '마지막으로 변경한 회원 ID',
  last_changed_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '사용자가 마지막으로 변경한 일시 (하이라이트 기준. 자리값만 다시 매길 때는 바꾸지 않는다)',
  created_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',
  updated_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '마지막 변경일시',
  PRIMARY KEY (id),
  UNIQUE KEY uq_schedule_items_place (schedule_id, place_key),
  KEY idx_schedule_items_day (schedule_id, day_no, start_time),
  KEY idx_schedule_items_place (place_id),
  KEY idx_schedule_items_changed_by (last_changed_by_user_id),
  CONSTRAINT fk_schedule_items_schedule   FOREIGN KEY (schedule_id) REFERENCES schedules (id) ON DELETE CASCADE,
  CONSTRAINT fk_schedule_items_place      FOREIGN KEY (place_id) REFERENCES places (id) ON DELETE CASCADE,
  CONSTRAINT fk_schedule_items_changed_by FOREIGN KEY (last_changed_by_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='일정항목';

-- ------------------------------------------------------------
-- 9. 채팅 (F6)
--    SYSTEM 메시지(공동 일정 변경 자동 기록)는 sender_user_id가 NULL
--      개인 일정의 변경은 채팅에 남기지 않는다.
--    shared_place_id : 장소 카드 공유 메시지일 때 해당 카드 (F6-7). 카드가 삭제되면 NULL
--      같은 여행방의 공동(SHARED) 카드만 공유할 수 있다. (나만 카드는 다른 멤버가 열 수 없다)
-- ------------------------------------------------------------
CREATE TABLE chat_messages (
  id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '채팅 메시지 ID',
  trip_id          BIGINT        NOT NULL COMMENT '여행방 ID',
  sender_user_id   BIGINT        NULL     COMMENT '보낸 회원 ID (시스템 메시지는 NULL)',
  message_type     ENUM('USER','SYSTEM') NOT NULL DEFAULT 'USER' COMMENT '메시지종류: USER, SYSTEM',
  content          VARCHAR(2000) NOT NULL COMMENT '메시지내용',
  shared_place_id  BIGINT        NULL     COMMENT '공유한 장소카드 ID',
  created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '전송일시',
  PRIMARY KEY (id),
  KEY idx_chat_messages_trip (trip_id, id),
  KEY idx_chat_messages_sender (sender_user_id),
  KEY idx_chat_messages_place (shared_place_id),
  CONSTRAINT fk_chat_messages_trip   FOREIGN KEY (trip_id) REFERENCES trips (id) ON DELETE CASCADE,
  CONSTRAINT fk_chat_messages_sender FOREIGN KEY (sender_user_id) REFERENCES users (id),
  CONSTRAINT fk_chat_messages_place  FOREIGN KEY (shared_place_id) REFERENCES places (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='채팅';
