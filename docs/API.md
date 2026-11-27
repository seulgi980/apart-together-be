# 따로같이 API 목록

프론트를 붙일 때 쓰는 문서다. 서버 코드와 테스트(`src/test`)가 이 문서의 근거다.

- 기본 주소(로컬): `http://localhost:8080`
- 모든 요청/응답 본문은 JSON(UTF-8). ID는 숫자다.
- 날짜 `YYYY-MM-DD`, 일정 시간 `HH:mm`(현지 시각 그대로, 시간대 변환 없음), 일시는 ISO-8601 UTC(`2026-11-18T08:30:00Z`).
- enum 값은 DB와 같은 대문자다. (`SHARED/PERSONAL`, `RESTAURANT/CAFE/SIGHTSEEING/LODGING/SHOPPING/ETC`, `LIKE/PASS` ...)

## 1. 인증 방식

| 토큰 | 전달 | 수명 | 쓰임 |
|---|---|---|---|
| access token (JWT) | 응답 본문 `accessToken`. 프론트가 메모리에 두고 `Authorization: Bearer <token>` 헤더로 보낸다 | 15분 | 모든 기능 API, WebSocket CONNECT |
| refresh token | `refresh_token` 쿠키 (HttpOnly, Secure, SameSite=Lax, Path=`/api/auth/token`) | 14일 | 재발급, 로그아웃만 |

- 쿠키를 주고받는 요청(`/api/auth/**`)은 `fetch(..., { credentials: "include" })`로 보낸다.
- 새로고침하면 메모리의 access token이 사라지므로, 앱을 시작할 때 `POST /api/auth/token/refresh`를 한 번 부른다. 200이면 로그인 상태, 401이면 비로그인.
- API가 401(`UNAUTHORIZED`)을 주면 재발급을 한 번 시도하고 원래 요청을 다시 보낸다. 재발급도 401이면 로그인 화면으로 보낸다.
- 재발급할 때마다 refresh token이 바뀐다(회전). **재발급 요청을 동시에 두 번 보내면 안 된다.** 이미 쓴 토큰이 다시 오면 서버는 탈취로 보고 그 회원의 refresh token을 전부 무효로 한다. (프론트는 진행 중인 재발급 Promise 하나를 공유한다)
- 방과 관련된 API는 토큰이 유효해도 매번 DB에서 "이 방의 ACTIVE 멤버인지" 확인한다. 아니면 403 `NOT_TRIP_MEMBER`.

## 2. 오류 응답

```json
{ "code": "SCHEDULE_OVERLAP", "message": "해당 시간에 이미 일정이 있습니다.", "conflict": { "dayNo": 3, "startTime": "13:00" } }
```

`code`와 `message`는 항상 있고, 코드에 따라 추가 정보가 붙는다.

| HTTP | code | 뜻 | 추가 정보 |
|---|---|---|---|
| 400 | `VALIDATION_FAILED` | 입력값 오류 | `fields`: { 필드명: 메시지 } (있을 때) |
| 401 | `UNAUTHORIZED` | access token 없음/만료 | |
| 401 | `INVALID_CREDENTIALS` | 아이디 또는 비밀번호 틀림 | |
| 401 | `INVALID_REFRESH_TOKEN` | refresh token 없음/만료/무효/재사용 | |
| 409 | `LOGIN_ID_TAKEN`, `NICKNAME_TAKEN` | 중복 | |
| 400 | `INVALID_IMAGE` | 프로필 사진 형식/크기/주소 오류 | |
| 403 | `NOT_TRIP_MEMBER` | 이 방의 ACTIVE 멤버가 아님 (방이 없을 때도 같다) | |
| 403 | `OWNER_ONLY` | 방장만 가능 | |
| 409 | `OWNER_CANNOT_LEAVE` | 방장은 나갈 수 없음 (방 삭제를 써야 함) | |
| 404 | `INVITE_NOT_FOUND` | 초대코드 없음 | |
| 409 | `TRIP_FULL` | 참여 인원이 가득 참 | |
| 403 | `KICKED_MEMBER` | 내보내진 사람은 다시 들어올 수 없음 | |
| 400 | `TRIP_TOO_LONG` | 여행 기간이 최대 일수 초과 | `maxDays`, `days` |
| 409 | `TRIP_PERIOD_HAS_ITEMS` | 기간을 줄이면 지워지는 일정이 있음 (확인 필요) | `itemCount`, `lostDayCount` |
| 409 | `PIN_COLOR_TAKEN` | 다른 멤버가 쓰는 색 | |
| 404 | `MEMBER_NOT_FOUND` | 내보낼 멤버가 없음 | |
| 404 | `PLACE_NOT_FOUND` | 카드 없음 (다른 방의 카드도 같은 응답) | |
| 409 | `PLACE_DUPLICATE` | 이 방에 이미 등록된 장소 (방마다 같은 장소는 1장) | |
| 400 | `NOT_GOOGLE_MAPS_URL` | 구글맵 링크가 아님 | |
| 422 | `PLACE_RESOLVE_FAILED` | 링크를 열지 못했거나 구글에서 그 장소를 찾지 못함 | |
| 503 | `GOOGLE_KEY_MISSING` | 서버에 `GOOGLE_MAPS_API_KEY`가 없어 장소를 등록할 수 없음 | |
| 404 | `REFERENCE_NOT_FOUND` | 참고자료 없음 | |
| 403 | `SCHEDULE_READ_ONLY` | 다른 멤버의 개인 일정은 읽기 전용 | |
| 404 | `SCHEDULE_NOT_FOUND`, `SCHEDULE_ITEM_NOT_FOUND` | 일정/항목 없음 | |
| 409 | `SCHEDULE_OVERLAP` | 시간 겹침 (F4-19) | `conflict`: { `dayNo`, `startTime` } |
| 409 | `SCHEDULE_GAP_MISMATCH` | 틈에 넣을 수 없는 시간 | |
| 409 | `SCHEDULE_ORDER_INVALID` | 보낸 순서를 적용할 수 없음 (그 사이 일정이 바뀜, 시간 항목 순서가 틀림) | |
| 400 | `INVALID_DAY` | 여행 기간 밖의 Day | |
| 400 | `INVALID_TIME_RANGE` | 종료가 시작보다 빠르거나 같음, 시작 없이 종료만 있음 | |
| 400 | `SHARE_NOT_ALLOWED` | 이 방의 카드가 아님 | |
| 409 | `CONFLICT` | 그 밖의 동시 변경 충돌 | |
| 500 | `INTERNAL_ERROR` | 서버 오류 | |

## 3. 설정값

### `GET /api/config` (로그인 불필요)
```json
{ "maxMembers": 10, "maxTripDays": 99, "pinColorCount": 10, "highlightSeconds": 30, "serverTime": "2026-11-18T08:30:00.123Z" }
```

## 4. 회원 (F1)

`User` = `{ "id": 1, "loginId": "jieun_01", "nickname": "지은", "profileImageUrl": null }`

`AuthResponse` = `{ "accessToken": "...", "expiresIn": 900, "user": User }` + `Set-Cookie: refresh_token=...`

| 메서드 | 경로 | 요청 | 응답 | 오류 |
|---|---|---|---|---|
| POST | `/api/auth/signup` | `{ loginId, password, nickname }` | 201 `AuthResponse` | `VALIDATION_FAILED`, `LOGIN_ID_TAKEN`, `NICKNAME_TAKEN` |
| POST | `/api/auth/login` | `{ loginId, password }` | 200 `AuthResponse` | `INVALID_CREDENTIALS` |
| GET | `/api/auth/availability?loginId=` 또는 `?nickname=` | | `{ "available": true }` | |
| POST | `/api/auth/token/refresh` | (쿠키) | 200 `AuthResponse` (쿠키도 새로 발급) | `INVALID_REFRESH_TOKEN` |
| POST | `/api/auth/token/logout` | (쿠키) | 204, 쿠키 삭제 | |
| GET | `/api/users/me` | | `User` | |
| PATCH | `/api/users/me` | `{ nickname, profileImageUrl }` | `User` | `NICKNAME_TAKEN`, `INVALID_IMAGE` |
| POST | `/api/users/me/profile-image` | `multipart/form-data`, 필드 `file` (PNG/JPG/WEBP/GIF, 2MB 이하) | `{ "url": "http://localhost:8080/uploads/profile/..." }` | `INVALID_IMAGE` |

- 입력 규칙: 아이디 `^[a-z0-9_]{4,16}$`, 닉네임 `^[가-힣a-zA-Z0-9]{2,10}$`, 비밀번호 `^[A-Za-z0-9!@#$%^&*]{8,20}$`.
- 프로필 사진: 먼저 업로드해서 받은 `url`을 `PATCH /api/users/me`의 `profileImageUrl`로 보낸다. `null`이면 기본 이미지. (업로드 API가 준 주소만 받는다)
- 프로필을 바꾸면 참여 중인 모든 방에 `MEMBER_UPDATED` 이벤트가 나간다.

## 5. 여행방 (F1)

```json
Trip = {
  "id": 1, "title": "도쿄 여행", "startDate": "2026-12-01", "endDate": "2026-12-05", "dayCount": 5,
  "ownerUserId": 1, "inviteCode": "K7M2QX9A",
  "members": [ { "userId": 1, "nickname": "지은", "profileImageUrl": null, "pinColorNo": 1, "joinedAt": "..." } ],
  "formerMembers": [ { "userId": 3, "status": "LEFT" } ],
  "unreadCount": 0
}
```
`members`는 ACTIVE 멤버만. `formerMembers`는 나간(`LEFT`)/내보내진(`KICKED`) 사람이고 화면에는 "탈퇴한 멤버"로 표시한다.

| 메서드 | 경로 | 요청 | 응답 | 오류 |
|---|---|---|---|---|
| GET | `/api/trips` | | `Trip[]` (내가 참여 중인 방) | |
| POST | `/api/trips` | `{ title, startDate, endDate }` | 201 `Trip` | `TRIP_TOO_LONG`, `VALIDATION_FAILED` |
| GET | `/api/trips/{tripId}` | | `Trip` | `NOT_TRIP_MEMBER` |
| PATCH | `/api/trips/{tripId}` | `{ title, startDate, endDate, deleteOutOfRangeItems? }` | `Trip` | `OWNER_ONLY`, `TRIP_TOO_LONG`, `TRIP_PERIOD_HAS_ITEMS` |
| DELETE | `/api/trips/{tripId}` | | 204 | `OWNER_ONLY` |
| POST | `/api/trips/{tripId}/invite-code` | | `Trip` (새 초대코드) | `OWNER_ONLY` |
| POST | `/api/trips/join` | `{ inviteCode }` | `{ "status": "JOINED" \| "ALREADY", "tripId": 1 }` | `INVITE_NOT_FOUND`, `TRIP_FULL`, `KICKED_MEMBER` |
| POST | `/api/trips/{tripId}/leave` | | 204 | `OWNER_CANNOT_LEAVE` |
| DELETE | `/api/trips/{tripId}/members/{userId}` | | 204 (내보내기) | `OWNER_ONLY`, `MEMBER_NOT_FOUND` |
| PATCH | `/api/trips/{tripId}/members/me` | `{ pinColorNo }` | `Member` | `PIN_COLOR_TAKEN` |

- 기간을 줄여서 사라지는 Day에 일정이 있으면 `TRIP_PERIOD_HAS_ITEMS`로 거절한다. 화면에서 확인을 받은 뒤 `deleteOutOfRangeItems: true`로 다시 보내면 그 항목을 지우고 수정한다.
- 나가기/내보내기: 그 사람의 투표, 개인 일정만 지운다. 장소 카드, 참고자료, 채팅은 남는다.
- 재참여: 스스로 나간 사람은 초대코드로 다시 들어올 수 있다(핀 색 새로 배정, 개인 일정 새로 생성, 안 읽은 수 0부터). 내보내진 사람은 `KICKED_MEMBER`.
- 초대코드는 대소문자를 가리지 않는다.

## 6. 장소 카드, 참고자료, 투표 (F2, F3)

```json
Place = {
  "id": 10, "tripId": 1, "placeKey": "ChIJ8T1GpMGOGGARDYGSgpooDWw",
  "name": "센소지", "nameEn": "Sensō-ji", "address": "...", "addressEn": "...", "area": "아사쿠사", "areaEn": "Asakusa", "latitude": 35.714765, "longitude": 139.796655,
  "googleMapsUrl": "https://maps.app.goo.gl/...", "category": "SIGHTSEEING", "memo": null,
  "createdByUserId": 1,
  "votes": { "1": "LIKE", "2": "PASS" },
  "references": [ { "id": 5, "url": "https://...", "title": "후기", "memo": null, "addedByUserId": 2 } ],
  "createdAt": "..."
}
```
- `address`, `area`, `latitude`, `longitude`, `memo`는 `null`일 수 있다. 좌표가 없으면 지도에 표시하지 않는다.
- 취향 상태(좋아요/패스/의견갈림/미평가)는 저장하지 않는다. `votes`와 현재 멤버 수로 화면이 계산한다. (`lib/domain/status.ts`)
- 장소 카드는 모두 방의 멤버 모두가 함께 쓴다(공개 범위 구분 없음). 방마다 같은 장소(`placeKey`)는 1장이다.
- 목록은 방의 카드 전체다. 누가 등록했든 멤버라면 수정/삭제/투표/참고자료 추가를 할 수 있다.

| 메서드 | 경로 | 요청 | 응답 | 오류 |
|---|---|---|---|---|
| POST | `/api/places/resolve` | `{ url }` | `{ placeKey, name, nameEn, address, addressEn, area, areaEn, latitude, longitude, googleMapsUrl, category }` | `NOT_GOOGLE_MAPS_URL`, `PLACE_RESOLVE_FAILED`, `GOOGLE_KEY_MISSING` |
| GET | `/api/trips/{tripId}/places` | | `Place[]` | |
| POST | `/api/trips/{tripId}/places` | `{ placeKey, name, nameEn?, address?, addressEn?, area?, areaEn?, latitude?, longitude?, googleMapsUrl, category, memo? }` | 201 `Place` (채팅에 등록 알림도 남긴다) | `PLACE_DUPLICATE`, `NOT_GOOGLE_MAPS_URL` |
| GET | `/api/trips/{tripId}/places/{placeId}` | | `Place` | `PLACE_NOT_FOUND` |
| GET | `/api/trips/{tripId}/places/{placeId}/google-info` | | `{ available, info: { rating, websiteUrl, weekdayHours[] } }` | `PLACE_NOT_FOUND` |
| PATCH | `/api/trips/{tripId}/places/{placeId}` | `{ name, category, memo }` | `Place` | |
| DELETE | `/api/trips/{tripId}/places/{placeId}` | | 204 (참고자료, 투표, 배치된 일정 항목도 삭제) | |
| PUT | `/api/trips/{tripId}/places/{placeId}/vote` | `{ vote: "LIKE" \| "PASS" }` | `Place` | |
| DELETE | `/api/trips/{tripId}/places/{placeId}/vote` | | `Place` (투표 취소) | |
| POST | `/api/trips/{tripId}/places/{placeId}/references` | `{ url, title, memo? }` | 201 `Place` | |
| PATCH | `/api/trips/{tripId}/places/{placeId}/references/{referenceId}` | `{ url, title, memo? }` | `Place` | `REFERENCE_NOT_FOUND` |
| DELETE | `/api/trips/{tripId}/places/{placeId}/references/{referenceId}` | | `Place` | `REFERENCE_NOT_FOUND` |

- 링크 해석(`/api/places/resolve`)은 짧은 링크를 열어 긴 주소를 얻고, 거기서 검색어로 쓸 장소명과 핀 좌표를 뽑는다. 그 값으로 구글 Places API(Text Search)에서 장소를 찾아 `placeKey`(= **구글 장소 ID** `ChIJ...`), 이름·주소·지역명(한국어), 좌표, `category`를 모두 구글 값으로 채운다.
  - `nameEn`, `addressEn`, `areaEn`은 같은 장소를 영어로 한 번 더 조회(Place Details, `languageCode=en`)해서 받은 값이다. 한국어 값과 같거나 받지 못하면 `null`. 등록할 때 그대로 보내면 `places.name_en`, `places.address_en`, `places.area_en`에 저장된다.
  - 화면은 지역명을 `영어 (한국어)`로 보여준다. 둘 중 하나만 있으면 있는 값만 보여준다.
  - 서버에 `GOOGLE_MAPS_API_KEY`가 없으면 `GOOGLE_KEY_MISSING`, 구글에서 장소를 찾지 못하면 `PLACE_RESOLVE_FAILED`다. 링크에서 읽은 값만으로 대신 등록하지 않는다.
- 구글 정보(`google-info`)는 DB에 저장하지 않고 부를 때마다 구글에서 받아 온다. 키가 없거나 구글에서 못 찾으면 `available: false`이고 `info`의 값은 모두 비어 있다(화면은 빈 칸으로 보여준다). `weekdayHours`는 월요일부터의 요일별 문구다.

## 7. 일정 (F4)

```json
ScheduleItem = {
  "id": 100, "tripId": 1, "scheduleId": 7,
  "scope": "SHARED", "ownerUserId": null,
  "dayNo": 3, "kind": "PLACE", "placeId": 10, "placeName": "센소지", "placeCategory": "SIGHTSEEING",
  "title": null, "startTime": "09:30", "endTime": "11:00", "memo": null,
  "anchorMinute": null, "sortOrder": 0,
  "lastChangeType": "ADDED", "lastChangedByUserId": 1, "lastChangedAt": "2026-11-18T08:30:00Z"
}
```
- `scope`: `SHARED`(공동 일정, `ownerUserId` = null) / `PERSONAL`(개인 일정, `ownerUserId` = 주인).
- `kind`: `PLACE`(장소 항목) / `MEMO`(메모 항목, `title` 있음).
- `placeName`, `placeCategory`: 장소 항목의 장소명과 카테고리. 카드가 지워졌으면 null.
- `startTime`이 null이면 시간 미정이고, 자리는 `anchorMinute`(-1 = 맨 위, null = 맨 아래, 그 외 = 바로 앞 시간 항목의 시작 분)와 `sortOrder`다. 화면 정렬은 `lib/domain/schedule.ts`의 `sortTimeline`과 같다.
- 하이라이트: `lastChangedAt`이 `highlightSeconds` 안이면 `lastChangedByUserId`의 닉네임과 `lastChangeType`(`ADDED`/`MOVED`/`TIME_CHANGED`/`EDITED`)으로 강조한다.

경로의 `{owner}`는 `shared`(공동 일정) 또는 회원 ID(그 회원의 개인 일정)다. 남의 개인 일정을 바꾸려 하면 `SCHEDULE_READ_ONLY`.

| 메서드 | 경로 | 요청 | 응답 | 오류 |
|---|---|---|---|---|
| GET | `/api/trips/{tripId}/schedule-items` | | `ScheduleItem[]` (공동 + 모든 멤버의 개인 일정) | |
| POST | `/api/trips/{tripId}/schedules/{owner}/items` | `{ dayNo, placeId? , title?, startTime?, endTime?, memo?, index? }` | 201 `ScheduleItem` | `SCHEDULE_OVERLAP`, `SCHEDULE_GAP_MISMATCH`, `PLACE_NOT_FOUND`, `INVALID_DAY`, `INVALID_TIME_RANGE` |
| PATCH | `/api/trips/{tripId}/schedule-items/{itemId}` | `{ startTime?, endTime?, memo?, title? }` | `ScheduleItem` | `SCHEDULE_OVERLAP`, `INVALID_TIME_RANGE` |
| POST | `/api/trips/{tripId}/schedule-items/{itemId}/move` | `{ dayNo }` | `ScheduleItem` | `INVALID_DAY` |
| DELETE | `/api/trips/{tripId}/schedule-items/{itemId}` | | 204 | |
| PUT | `/api/trips/{tripId}/schedules/{owner}/days/{dayNo}/order` | `{ orderedIds: [..], movedId }` | `ScheduleItem[]` (그 Day, 화면 순서) | `SCHEDULE_ORDER_INVALID` |

- **추가**: `placeId`가 있으면 장소 항목, 없으면 메모 항목(`title` 필수, 30자).
  - 같은 장소(카드)를 한 일정에 몇 번이든 넣을 수 있다. 시간이 겹치는 것만 `SCHEDULE_OVERLAP`으로 거절한다. (F4-20)
  - `index`를 주면 타임라인의 그 자리(0 = 맨 위)에 끼워 넣는다. `startTime`도 함께 주면 그 시간이 앞뒤 시간 항목 사이에 들어가야 한다.
  - `index`가 없고 `startTime`도 없으면 그 Day의 맨 아래 시간 미정으로 들어간다. ([+] 버튼)
- **수정**: 보낸 키만 바꾼다. `startTime` 키가 있으면 시간 변경이다(`null`이면 시간 미정으로). 이때 `endTime`은 함께 보낸 값(없으면 null)으로 바뀐다. `title`은 메모 항목만.
  - 시간 → 시간 미정: 있던 자리에 남는다. 시간 미정 → 시간: 시간순 자리로 간다.
- **Day 이동**: 시간이 초기화되고 옮겨 간 Day의 맨 아래 시간 미정으로 놓인다. 겹침 검증 없음.
- **순서**: 그 Day의 모든 항목 ID를 화면 순서대로 보낸다. 시간 항목끼리는 시간순이어야 한다. `movedId`(끌어 옮긴 항목)만 "방금 이동됨"으로 표시된다.
- 모든 변경은 일정 행을 잠근 트랜잭션에서 검증하므로, 동시에 들어온 요청은 먼저 저장된 것이 우선하고 늦은 요청은 `SCHEDULE_OVERLAP`으로 거절된다.
- 공동 일정의 추가/시간 변경/Day 이동/제외는 채팅에 시스템 메시지를 남긴다. 개인 일정은 남기지 않는다.

## 8. 채팅 (F6)

```json
ChatMessage = { "id": 55, "tripId": 1, "kind": "USER", "senderUserId": 2, "content": "안녕", "sharedPlaceId": null, "clientMessageId": "6f1c...", "createdAt": "..." }
```
- `kind`: `USER`(일반) / `SYSTEM`(공동 일정 변경, 장소 카드 등록 기록, `senderUserId` = null) / `PLACE`(장소 카드 공유, `content`는 빈 문자열).
- 장소 카드 등록 알림(`SYSTEM`)은 `"OO님이 XX 장소 카드를 추가했습니다."`이고 `sharedPlaceId`에 그 카드 ID가 실린다. 이벤트는 알림(`CHAT_MESSAGE`)이 카드(`PLACE_UPSERTED`)보다 먼저 온다.
  화면은 이 알림을 받은 카드만 채팅 [+] 목록에 보여준다. (카드가 채팅 알림보다 먼저 공유되지 않게)
- `kind`가 `PLACE`인데 `sharedPlaceId`가 null이면 카드가 삭제된 것이다("삭제된 장소 카드").
- `clientMessageId`: 보낼 때 화면이 붙인 번호표(영문·숫자·하이픈 1~36자, 보통 UUID)를 그대로 돌려준다. 시스템 메시지와 번호표 없이 보낸 메시지는 null.
  같은 사람이 같은 번호표로 다시 보내면(재전송) 새로 저장하지 않고 이미 저장된 메시지를 돌려주고 `CHAT_MESSAGE` 이벤트를 다시 보낸다.

| 메서드 | 경로 | 요청 | 응답 | 오류 |
|---|---|---|---|---|
| GET | `/api/trips/{tripId}/chat/messages?before={id}&limit={n}` | | `{ "messages": ChatMessage[], "hasMore": true }` | |
| POST | `/api/trips/{tripId}/chat/messages` | `{ content }` 또는 `{ sharedPlaceId }`, 둘 다 `clientMessageId?` | 201 `ChatMessage` | `VALIDATION_FAILED`, `SHARE_NOT_ALLOWED` |
| GET | `/api/trips/{tripId}/chat/unread` | | `{ "unreadCount": 3, "lastReadMessageId": 52 }` | |
| POST | `/api/trips/{tripId}/chat/read` | `{ lastReadMessageId? }` | `{ "unreadCount": 0, "lastReadMessageId": 55 }` | |

- 메시지 조회: `messages`는 오래된 것부터. `before`가 없으면 가장 최근 쪽(기본 50개, 최대 100개). `hasMore`가 true면 첫 메시지의 `id`를 `before`로 넘겨 위쪽을 이어 불러온다.
- 안 읽은 수: 마지막으로 읽은 메시지 뒤에 온, 남이 보낸 말풍선 메시지(`USER`, `PLACE`) 수. 시스템 알림(`SYSTEM`)은 세지 않는다. `read`에 `lastReadMessageId`를 생략하면 방의 마지막 메시지까지 읽은 것으로 한다.
- 전송은 REST로도, WebSocket으로도 할 수 있다. 어느 쪽이든 `CHAT_MESSAGE` 이벤트로 모두에게 전달된다.

## 9. 실시간 (WebSocket, STOMP) (F7)

- 연결 주소: `ws://localhost:8080/ws` (SockJS 아님. `@stomp/stompjs`의 `brokerURL`로 쓴다)
- 인증: STOMP **CONNECT 프레임 헤더** `Authorization: Bearer <access token>`. 없거나 틀리면 ERROR 프레임과 함께 연결이 끊긴다.
- 허용한 프론트 주소(Origin)에서 온 연결만 받는다.
- 구독
  - `/topic/trips/{tripId}`: 방의 모든 이벤트. 구독할 때 그 방의 ACTIVE 멤버인지 DB에서 확인한다. 아니면 ERROR 프레임과 함께 연결이 끊긴다.
  - `/user/queue/errors`: 내가 WebSocket으로 보낸 요청이 거절됐을 때 `{ code, message }`가 온다.
- 전송
  - `/app/trips/{tripId}/chat`: `{ "content": "..." }` 또는 `{ "sharedPlaceId": 10 }`, 둘 다 `"clientMessageId"`를 붙일 수 있다.
    거절되면 `/user/queue/errors`로 `{ code, message, clientMessageId }`가 간다.
  - `/app/trips/{tripId}/presence`: `{ "day": 3 }` (지금 보고 있는 Day. Day를 바꿀 때와 구독 직후에 보낸다)
- 현재 "보는 중" 상태: `GET /api/trips/{tripId}/presence` → `{ "viewers": [ { "userId": 1, "day": 3 } ] }`
- access token이 연결 도중 만료돼도 연결은 유지된다. 다시 연결할 때는 새 access token을 CONNECT에 싣는다.
- 나가거나 내보내진 멤버는 `MEMBER_REMOVED` 이벤트를 받은 직후 서버가 연결을 끊는다. 다시 연결해 그 방을 구독하면 거절된다.

### 이벤트 형식

```json
{ "type": "CHAT_MESSAGE", "tripId": 1, "actorUserId": 2, "payload": { ... }, "at": "2026-11-18T08:30:00Z" }
```
모든 이벤트는 **DB에 저장(커밋)된 뒤에** 나간다. 거절된 요청은 이벤트가 없다. 변경을 요청한 본인에게도 온다.

| type | 언제 | payload |
|---|---|---|
| `CHAT_MESSAGE` | 채팅 전송, 공동 일정 변경의 시스템 메시지, 장소 카드 공유 | `ChatMessage` |
| `SCHEDULE_CHANGED` | 일정 항목 추가/수정/Day 이동/순서 변경/제외, 장소 카드 삭제·수정으로 항목이 바뀜 | `{ "scope": "SHARED"\|"PERSONAL", "ownerUserId": null\|1, "upserted": ScheduleItem[], "removedIds": [100] }` |
| `PLACE_UPSERTED` | 공동 카드 등록/수정, 참고자료 변경, 공동으로 올리기 | `Place` |
| `PLACE_DELETED` | 공동 카드 삭제 | `{ "placeId": 10, "removedItemIds": [100] }` |
| `VOTE_CHANGED` | 투표/취소 | `{ "placeId": 10, "userId": 1, "vote": "LIKE"\|"PASS"\|null }` |
| `MEMBER_JOINED` | 참여, 재참여 | `{ userId, nickname, profileImageUrl, pinColorNo, joinedAt }` |
| `MEMBER_REMOVED` | 나가기, 내보내기 | `{ "userId": 2, "status": "LEFT"\|"KICKED" }` |
| `MEMBER_UPDATED` | 핀 색 변경 / 닉네임·프로필 사진 변경 | `{ userId, pinColorNo }` 또는 `{ userId, nickname, profileImageUrl }` (온 키만 반영) |
| `TRIP_UPDATED` | 제목·기간 수정, 초대코드 재발급 | `{ id, title, startDate, endDate, dayCount, inviteCode, removedItemIds }` |
| `TRIP_DELETED` | 방 삭제 | `{}` |
| `PRESENCE_CHANGED` | "보는 중" 변경, 연결 끊김 | `{ "viewers": [ { "userId": 1, "day": 3 } ] }` (그 방의 전체 상태) |

화면 반영 요령
- `SCHEDULE_CHANGED`: `upserted`는 ID 기준으로 넣거나 바꾸고, `removedIds`는 지운다.
- `MEMBER_REMOVED`: 그 사람의 투표, 개인 일정이 서버에서 지워졌다. 방 정보, 장소 카드, 일정을 다시 불러오는 것이 가장 간단하다. 내가 대상이면 방 목록으로 나간다.
- `PLACE_DELETED`: 카드와 `removedItemIds`의 일정 항목을 지운다.
- 연결이 끊겼다 다시 붙으면 그 사이 이벤트를 놓쳤을 수 있으므로 방 데이터를 다시 불러온다.

## 10. 서버 확장 메모 (다음 단계)

서버 메모리에 두는 상태는 아래 두 가지뿐이고 둘 다 인터페이스다. 컨테이너 3대 + Redis로 늘릴 때 구현만 바꿔 끼운다.

| 인터페이스 | 지금 구현 | 다음 단계 |
|---|---|---|
| `realtime/TripEventPublisher` | `InMemoryTripEventPublisher` (이 서버에 연결된 사람에게 바로 전달) | Redis Pub/Sub으로 발행하고, 각 서버의 구독자가 `LocalTripEventDelivery.deliver()` 호출 |
| `realtime/PresenceStore` | `InMemoryPresenceStore` | Redis 해시 (연결이 죽은 서버의 값은 TTL로 정리) |

로그인 상태(세션)와 토큰은 서버 메모리에 두지 않는다. refresh token은 MySQL `refresh_tokens`에 해시로 저장한다.
프로필 사진은 지금 서버의 로컬 폴더(`APP_UPLOAD_DIR`)에 저장한다. 서버를 여러 대로 늘릴 때는 공유 저장소(S3 등)로 바꿔야 한다.
