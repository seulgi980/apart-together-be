# EC2 배포

```
브라우저 ──https──> nginx (EC2 호스트, 80/443, certbot 인증서)
                      ├─ /api, /uploads, /ws ─> backend  컨테이너 (127.0.0.1:8080)
                      └─ 그 밖의 전부         ─> frontend 컨테이너 (127.0.0.1:3000)
                                                 backend ─> mysql 컨테이너 (밖으로 열지 않음)
```

- 프론트와 백엔드가 같은 도메인으로 나가므로 CORS와 쿠키 설정이 단순하다.
- `git push` (main) → GitHub Actions가 테스트 → 이미지 빌드(GHCR) → EC2에서 새 이미지로 교체.
  백엔드 저장소를 push하면 백엔드만, 프론트 저장소를 push하면 프론트만 바뀐다.

## 비밀 값이 있는 곳

| 값 | 있는 곳 | 없는 곳 |
|---|---|---|
| MySQL 비밀번호, JWT 키, 구글 서버 키 | EC2의 `~/apart-together/.env` (권한 600) | git, GitHub, 도커 이미지 |
| 배포용 SSH 개인 키, EC2 주소 | GitHub Actions Secrets | git |
| 지도용 구글 키(브라우저에 보이는 값), 도메인 | 프론트 저장소의 Actions Secrets / Variables | git |

GitHub Actions는 `.env` 를 읽지도 만들지도 않는다. EC2에서 이미지를 받을 때 쓰는 토큰은 작업이 끝나면 만료되고 EC2에 남기지 않는다.

## 처음 한 번 하는 일

### 1. 도메인 연결 (가비아)
가비아 DNS 관리에서 A 레코드를 추가한다: 호스트 `@` → EC2의 퍼블릭 IP (`www`도 쓸 거면 하나 더).
EC2를 껐다 켜도 IP가 바뀌지 않게 탄력적 IP(Elastic IP)를 붙여 둔다.

### 2. EC2 보안 그룹
| 포트 | 허용 대상 | 용도 |
|---|---|---|
| 80, 443 | 전체 | 웹 |
| 22 | 전체 (키 인증만) | 내 접속 + GitHub Actions 배포 |

3306, 8080, 3000 은 열지 않는다. (GitHub Actions의 IP가 매번 달라서 22번은 IP로 좁힐 수 없다. 대신 비밀번호 로그인은 끄고 키로만 접속한다.)

### 3. 배포용 SSH 키 만들기 (내 PC에서)
내가 쓰는 키와 따로 만든다. 유출되면 이 키만 지우면 된다.

```bash
ssh-keygen -t ed25519 -f apart-deploy -N "" -C "github-actions-deploy"
```

- `apart-deploy.pub` 내용을 EC2의 `~/.ssh/authorized_keys` 에 한 줄 추가한다.
- `apart-deploy` (개인 키) 내용은 GitHub Secrets 의 `EC2_SSH_KEY` 에 넣는다. 넣은 뒤 PC의 파일은 지운다.

### 4. EC2 준비
```bash
# 지금 계정이 sudo 없이 docker 를 쓸 수 있게 (한 번 로그아웃했다 들어와야 적용된다)
sudo usermod -aG docker $USER

mkdir -p ~/apart-together && cd ~/apart-together
```

### 5. GitHub 저장소 설정
두 저장소 모두 Settings > Secrets and variables > Actions 에서:

| 종류 | 이름 | 값 | 저장소 |
|---|---|---|---|
| Secret | `EC2_HOST` | 도메인 또는 EC2 IP | 둘 다 |
| Secret | `EC2_USER` | `ubuntu` | 둘 다 |
| Secret | `EC2_SSH_KEY` | 3번의 개인 키 전체 | 둘 다 |
| Secret | `NEXT_PUBLIC_GOOGLE_MAPS_API_KEY` | 지도용 구글 키 | 프론트 |
| Variable | `NEXT_PUBLIC_API_URL` | `https://도메인` | 프론트 |

### 6. 백엔드 첫 push → EC2에 `.env` 만들기
백엔드를 main에 push하면 Actions가 `docker-compose.yml`, `.env.example`, `db/*.sql` 을 EC2에 복사한 뒤
"`.env` 가 없습니다"로 멈춘다. (정상이다. 비밀 값은 사람이 직접 넣는다.)

```bash
cd ~/apart-together
cp .env.example .env
chmod 600 .env
nano .env        # 값 채우기
```

- `BACKEND_IMAGE` = `ghcr.io/깃허브아이디(소문자)/apart-together-be:latest`
- `FRONTEND_IMAGE` = `ghcr.io/깃허브아이디(소문자)/apart-together-fe:latest`
- 비밀번호와 JWT 키는 `openssl rand -base64 48` 로 만든다.

그다음 GitHub의 Actions 탭에서 실패한 실행을 **Re-run** 한다. 이어서 프론트를 push한다.

### 7. nginx + 인증서
```bash
sudo apt update
sudo apt install -y nginx certbot python3-certbot-nginx

# 이 저장소의 deploy/nginx/apart-together.conf 내용을 붙여넣고 YOUR_DOMAIN 을 내 도메인으로 바꾼다
sudo nano /etc/nginx/sites-available/apart-together
sudo ln -s /etc/nginx/sites-available/apart-together /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx

# 인증서 발급. 443 설정과 http -> https 이동을 certbot이 위 파일에 추가해 준다. 갱신도 자동이다.
sudo certbot --nginx -d 내도메인
```

`https://내도메인` 으로 접속해서 가입 → 방 만들기 → 장소 추가 → 지도까지 확인한다.

## 평소에는
코드를 고치고 `git push` 만 하면 된다. 진행 상황과 실패 로그는 저장소의 Actions 탭에 나온다.

## 서버에서 쓰는 명령
```bash
cd ~/apart-together
docker compose ps                       # 상태
docker compose logs -f --tail 100 backend
docker compose restart backend          # .env 를 고친 뒤에는: docker compose up -d
```

## 알아 둘 것
- **테이블 SQL은 DB가 비어 있는 첫 실행 때만 돈다.** 나중에 컬럼을 추가하면 운영 DB에는 직접 실행해야 한다.
  `docker compose exec mysql mysql -uroot -p apart_together`
- **DB 백업**: 데이터는 도커 볼륨(`apart-together_mysql-data`)에 있다. `docker compose down -v` 는 데이터를 지운다.
  ```bash
  docker compose exec mysql sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" apart_together' > backup-$(date +%F).sql
  ```
- **메모리**: MySQL + 자바 + 노드가 함께 뜬다. 메모리 1GB 인스턴스(t2.micro 등)는 부족할 수 있으니 스왑을 잡아 둔다.
  ```bash
  sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
  ```
- 배포하는 동안 해당 컨테이너는 수십 초 내려간다 (무중단 배포는 아니다).
- **구글 키**: 지도용 키(프론트)는 브라우저에 보이는 값이다.
  - 키를 하나만 쓰는 동안(데모 키)은 제한을 걸지 않는다. 웹사이트 제한이나 API 제한을 걸면 서버의 장소 검색까지 막힌다.
    이때의 위험은 누가 키를 가져가 하루 한도를 써 버리는 것이다. 그러면 키를 재발급해서 EC2의 `.env` 와 프론트 저장소의 Secret 을 바꾸고 프론트를 다시 배포한다.
  - 키를 두 개로 나눌 수 있게 되면: 프론트용은 "웹사이트 제한(내 도메인) + Maps JavaScript API만", 서버용은 "Places API만 + 서버 IP 제한"으로 건다.
