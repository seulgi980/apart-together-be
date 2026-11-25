# 따로같이 백엔드

Java 17, Spring Boot 4, Spring Data JPA, MySQL 8, WebSocket(STOMP), JWT.

- API 목록: [docs/API.md](docs/API.md)
- 테이블: [db/schema.sql](db/schema.sql) → [db/constraints.sql](db/constraints.sql) → [db/refresh_tokens.sql](db/refresh_tokens.sql) → [db/places_english.sql](db/places_english.sql) → [db/schedule_items_lodging_slot.sql](db/schedule_items_lodging_slot.sql) 순서로 실행

## 로컬에서 실행

1. MySQL에 데이터베이스와 테이블을 만든다.

   ```sql
   CREATE DATABASE apart_together CHARACTER SET utf8mb4;
   USE apart_together;
   SOURCE db/schema.sql;
   SOURCE db/constraints.sql;
   SOURCE db/refresh_tokens.sql;
   SOURCE db/places_english.sql;
   SOURCE db/schedule_items_lodging_slot.sql;
   ```

2. 로컬 설정 파일을 만들고 실행한다. 비밀 값(DB 비밀번호, JWT 서명 키, 구글 키)은 코드에 없다.

   프로젝트 폴더(`gradlew` 옆)에 `application-local.yaml` 을 만들고 아래 세 값을 채운다.

   ```yaml
   spring:
     datasource:
       password: ""          # 로컬 MySQL 비밀번호
   app:
     jwt:
       secret: ""            # 32글자 이상 아무 문자열 (로컬 전용)
     places:
       google-api-key: ""    # 구글 Places API 키
   ```

   ```bat
   gradlew bootRun
   ```

   `application-local.yaml` 은 git에 올라가지 않는다. 환경 변수(`DB_PASSWORD` 등)로 넣어도 된다.

3. 프론트(`http://localhost:3000`)에서 `http://localhost:8080`으로 붙는다.

## 환경 변수

| 이름 | 기본값 | 설명 |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/apart_together?...` | MySQL 주소 |
| `DB_USERNAME` | `root` | |
| `DB_PASSWORD` | (없음, 필수) | |
| `JWT_SECRET` | (없음, 필수) | access token 서명 키. 32바이트 이상 |
| `SERVER_PORT` | `8080` | |
| `APP_FRONT_ORIGINS` | `http://localhost:3000` | CORS와 WebSocket에서 허용할 프론트 주소. 쉼표로 여러 개 |
| `APP_PUBLIC_BASE_URL` | `http://localhost:8080` | 브라우저에서 이 서버를 부르는 주소 (프로필 사진 URL에 쓴다) |
| `APP_COOKIE_SECURE` | `true` | refresh 쿠키의 Secure 속성 |
| `APP_UPLOAD_DIR` | `./uploads` | 프로필 사진 저장 폴더 |
| `APP_MAX_MEMBERS` / `APP_MAX_TRIP_DAYS` / `APP_PIN_COLOR_COUNT` / `APP_HIGHLIGHT_SECONDS` | 10 / 99 / 10 / 30 | 서비스 설정값 |
| `APP_ACCESS_TTL` / `APP_REFRESH_TTL` | `15m` / `14d` | 토큰 수명 |
| `GOOGLE_MAPS_API_KEY` | (없음) | 구글 Places API (New) 키. 장소 ID·한글 이름·주소·지역명·좌표·카테고리와 상세의 평점·영업시간·웹사이트를 구글에서 받는다. **없으면 장소를 등록할 수 없다** |

EC2에 배포할 때 바꿀 것: `DB_URL`, `APP_FRONT_ORIGINS`, `APP_PUBLIC_BASE_URL` (프론트는 `NEXT_PUBLIC_API_URL`, `NEXT_PUBLIC_WS_URL`).
refresh 쿠키가 Secure + SameSite=Lax라서, 배포 환경에서는 프론트와 백엔드가 HTTPS이고 같은 사이트(예: `app.example.com`과 `api.example.com`)여야 한다.

## 환경 파일 (local / prod 두 가지)

| | 백엔드 | 프론트 |
|---|---|---|
| local (내 PC) | `application-local.yaml` (프로젝트 폴더, git 제외) | `.env.local` (git 제외) |
| prod (EC2) | EC2의 `~/apart-together/.env` (EC2에만 있음, 권한 600) | GitHub 저장소의 Actions Secrets / Variables (이미지를 빌드할 때 들어간다) |

배포 방법은 [deploy/README.md](deploy/README.md).

## 테스트

MySQL이 필요하다. 테스트를 시작할 때 테스트용 데이터베이스의 테이블을 지우고 `db/*.sql`로 다시 만들기 때문에,
**개발용과 다른 빈 데이터베이스**를 지정한다.

```powershell
# 한 번만: CREATE DATABASE apart_together_test CHARACTER SET utf8mb4;
$env:TEST_DB_URL      = "jdbc:mysql://localhost:3306/apart_together_test?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
$env:TEST_DB_USERNAME = "root"
$env:TEST_DB_PASSWORD = "MySQL 비밀번호"
.\gradlew test
```

`constraints.sql` 3부의 규칙마다 테스트가 있고, 테스트 이름 앞의 `[규칙 이름]`으로 찾을 수 있다.
