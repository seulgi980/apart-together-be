# EC2 배포 안내

위에서부터 차례대로 따라 하면 된다. 처음 한 번만 하고, 그 뒤로는 `git push` 만 하면 배포된다.

```
브라우저 ──https──> nginx (EC2에 직접 설치, 80/443, certbot 인증서)
                      ├─ /api, /uploads, /ws ─> backend  컨테이너 (127.0.0.1:8080)
                      └─ 그 밖의 전부         ─> frontend 컨테이너 (127.0.0.1:3000)
                                                 backend ─> mysql 컨테이너 (밖으로 열지 않음)
```

- `git push` → GitHub Actions가 검사 → 도커 이미지 빌드 → EC2에 접속해서 새 이미지로 교체.
- 백엔드 저장소를 push하면 백엔드만, 프론트 저장소를 push하면 프론트만 바뀐다.

## 전체 순서

| 단계 | 하는 곳 | 하는 일 |
|---|---|---|
| 1 | AWS 콘솔 | 탄력적 IP 붙이기 (고정 IP) |
| 2 | AWS 콘솔 | 보안 그룹 포트 열기 |
| 3 | 가비아 | 도메인을 EC2 IP에 연결 |
| 4 | 구글 콘솔 | API 키 2개에 제한 걸기 |
| 5 | EC2 | 폴더, `.env`, 스왑 준비 |
| 6 | 내 PC + EC2 | 배포용 SSH 키 만들기 |
| 7 | GitHub | Secrets 등록 (저장소 2개) |
| 8 | 내 PC | 백엔드 push → 프론트 push |
| 9 | EC2 | nginx + 인증서(https) |
| 10 | 브라우저 | 확인 |

진행하면서 아래 값을 메모해 둔다.

- **탄력적 IP** (1단계에서 생김): 3, 4, 7단계에서 쓴다.
- **도메인** (가비아에서 산 것. 예: `example.com`): 3, 4, 5, 7, 9단계에서 쓴다.
- **깃허브 아이디** (소문자로): 5단계에서 쓴다.

이 문서에서 `내도메인` 이라고 적힌 곳은 전부 실제 도메인으로 바꿔서 쓴다. `www` 는 쓰지 않고 `example.com` 하나로만 접속한다.

---

## 1. 탄력적 IP 붙이기

**탄력적 IP란**: EC2에 처음부터 붙어 있는 퍼블릭 IP는 인스턴스를 중지했다 시작하면 다른 숫자로 바뀐다. 그러면 도메인 연결과 구글 키 제한이 전부 틀어진다. 탄력적 IP(Elastic IP)는 내가 반납할 때까지 바뀌지 않는 고정 IP다. 도메인을 붙일 서버는 이것을 쓴다.

1. AWS 콘솔 > EC2 > 왼쪽 메뉴 **네트워크 및 보안 > 탄력적 IP** (Elastic IPs).
2. 오른쪽 위 **탄력적 IP 주소 할당** (Allocate) > 그대로 **할당**.
3. 방금 생긴 IP를 선택 > **작업 > 탄력적 IP 주소 연결** (Associate) > 인스턴스에서 내 EC2를 고르고 **연결**.

끝나면:

- 인스턴스의 퍼블릭 IP가 이 탄력적 IP로 **바뀐다**. 앞으로 SSH 접속도 이 IP로 한다. (예전 IP는 더 이상 안 된다.)
- 이 IP를 메모해 둔다.

비용: AWS는 퍼블릭 IPv4 주소에 시간당 요금을 받는다. 실행 중인 인스턴스에 붙어 있으면 원래 있던 퍼블릭 IP와 요금이 같다. **인스턴스에 붙이지 않고 놔두기만 해도 요금이 나오니**, 서버를 없앨 때는 탄력적 IP도 "릴리스"해서 반납한다.

## 2. 보안 그룹 포트 열기

EC2 > 인스턴스 선택 > **보안** 탭 > 보안 그룹 이름 클릭 > **인바운드 규칙 편집**.

| 유형 | 포트 | 소스 | 용도 |
|---|---|---|---|
| HTTP | 80 | 0.0.0.0/0 | 웹 (인증서 발급에도 필요) |
| HTTPS | 443 | 0.0.0.0/0 | 웹 |
| SSH | 22 | 0.0.0.0/0 | 내 접속 + GitHub Actions 배포 |

- 3306(MySQL), 8080(백엔드), 3000(프론트)은 **열지 않는다**. nginx를 거쳐서만 들어온다.
- 22번을 내 IP로만 좁히면 GitHub Actions가 접속하지 못한다 (GitHub 쪽 IP가 매번 다르다). 대신 EC2 우분투는 기본이 "키로만 접속, 비밀번호 접속 불가"라서 키가 없으면 들어올 수 없다.

## 3. 가비아 도메인을 EC2에 연결

"이 도메인으로 오면 이 IP로 보내라"는 기록(A 레코드)을 하나 추가하는 일이다.

1. 가비아 로그인 > **My가비아** > **도메인** > 산 도메인 옆 **관리**.
2. **DNS 정보** 의 **도메인 연결 설정** (또는 "DNS 관리툴") > 도메인 선택 > **DNS 설정 / 레코드 수정**.
3. **레코드 추가**:

   | 타입 | 호스트 | 값/위치 | TTL |
   |---|---|---|---|
   | A | `@` | 1단계의 탄력적 IP | 600 |

4. 저장.

(가비아 화면의 메뉴 이름은 조금 다를 수 있다. "DNS 레코드에 A 타입을 추가한다"만 맞으면 된다. 네임서버는 가비아 기본값 그대로 둔다.)

확인: 몇 분 뒤 내 PC의 PowerShell에서

```powershell
nslookup 내도메인
```

`Address` 에 탄력적 IP가 나오면 연결된 것이다. 보통 몇 분이면 되고 길면 한두 시간 걸린다. **이게 되어야 9단계의 인증서 발급이 된다.**

## 4. 구글 API 키 2개에 제한 걸기

키를 용도별로 나눠 쓴다. 구글 콘솔 > **Keys & Credentials** 에서 각 키를 눌러 설정한다.

| | 브라우저용 키 | 서버용 키 |
|---|---|---|
| 하는 일 | 지도 그리기 | 장소 검색·상세 |
| 누가 구글에 요청하나 | 사용자의 브라우저 | EC2의 백엔드 |
| Key restrictions | **Websites** | **IP addresses** |
| 넣을 주소 | `https://내도메인/*` 와 `http://localhost:3000/*` | 1단계의 탄력적 IP |
| Selected APIs | **Maps JavaScript API** 만 | **Places API (New)** 만 |
| 값을 넣는 곳 (운영) | 프론트 저장소 Secret `NEXT_PUBLIC_GOOGLE_MAPS_API_KEY` | EC2 `.env` 의 `GOOGLE_MAPS_API_KEY` |
| 값을 넣는 곳 (로컬) | 프론트 `.env.local` | 백엔드 `application-local.yaml` |

- 브라우저용 키에 `http://localhost:3000/*` 을 넣는 이유: 내 PC에서 개발할 때도 지도가 떠야 해서다.
- 서버용 키는 EC2 IP로만 제한하면 **내 PC(로컬)의 백엔드에서는 장소 추가가 막힌다.** 로컬에서도 장소 추가를 하려면 내 PC의 공인 IP도 같이 넣는다. PowerShell에서 `curl.exe ifconfig.me` 로 확인한다. 집 인터넷 IP는 가끔 바뀌니, 로컬에서 갑자기 장소 추가가 안 되면 이 값을 다시 확인한다.
- 브라우저용 키는 화면 코드에 들어가서 누구나 볼 수 있다. 그래서 "내 도메인에서 온 요청만, 지도 API만"으로 묶어 두는 것이다. 서버용 키는 EC2 밖으로 나가지 않는다.
- 저장하고 반영까지 몇 분 걸릴 수 있다.

## 5. EC2 준비

탄력적 IP로 SSH 접속한 뒤 진행한다.

### 5-1. docker 를 sudo 없이 쓰게 하기
```bash
sudo usermod -aG docker $USER
exit
```
다시 접속한 뒤 확인:
```bash
docker ps          # 오류 없이 빈 목록이 나오면 된다
docker compose version
```

### 5-2. 스왑 잡기 (메모리 1GB 인스턴스라면 꼭)
MySQL + 자바 + 노드가 같이 떠서 메모리가 모자랄 수 있다.
```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h            # Swap 줄에 2.0Gi 가 보이면 된다
```

### 5-3. 폴더와 `.env` 만들기
운영용 비밀 값은 이 파일에만 있다. git에도 GitHub에도 올리지 않는다.

```bash
mkdir -p ~/apart-together && cd ~/apart-together
touch .env && chmod 600 .env

# 비밀번호 2개와 JWT 키로 쓸 임의의 값 3개를 만든다. 나온 값을 복사해 둔다.
openssl rand -hex 24
openssl rand -hex 24
openssl rand -base64 48

nano .env
```

아래를 붙여넣고 `=` 뒤를 채운다. (`=` 양옆에 띄어쓰기 없이, 따옴표 없이)

```
DOMAIN=
BACKEND_IMAGE=
FRONTEND_IMAGE=
MYSQL_ROOT_PASSWORD=
MYSQL_PASSWORD=
JWT_SECRET=
GOOGLE_MAPS_API_KEY=
```

| 이름 | 넣을 값 |
|---|---|
| `DOMAIN` | `내도메인` (`https://` 없이. 예: `example.com`) |
| `BACKEND_IMAGE` | `ghcr.io/깃허브아이디/apart-together-be:latest` (아이디는 전부 소문자) |
| `FRONTEND_IMAGE` | `ghcr.io/깃허브아이디/apart-together-fe:latest` |
| `MYSQL_ROOT_PASSWORD` | 위에서 만든 첫 번째 값 |
| `MYSQL_PASSWORD` | 위에서 만든 두 번째 값 |
| `JWT_SECRET` | 위에서 만든 세 번째 값 |
| `GOOGLE_MAPS_API_KEY` | 4단계의 **서버용** 키 |

nano 저장: `Ctrl+O` → `Enter` → `Ctrl+X`.

**MySQL 비밀번호는 처음 띄울 때 정해지고, 그 뒤에 `.env` 만 바꿔서는 바뀌지 않는다.** 처음에 제대로 넣는다.

## 6. 배포용 SSH 키 만들기

GitHub Actions가 EC2에 접속할 때 쓰는 전용 열쇠다. 내가 평소 쓰는 키(.pem)와 따로 만든다. 문제가 생기면 이 키만 지우면 된다.

### 6-1. 내 PC의 PowerShell에서
```powershell
cd ~
ssh-keygen -t ed25519 -f apart-deploy -C "github-actions-deploy"
```
비밀번호(passphrase)를 물으면 **아무것도 입력하지 않고 Enter 두 번**. 파일 2개가 생긴다.

- `apart-deploy` : 개인 키 (열쇠). GitHub Secrets에 넣는다.
- `apart-deploy.pub` : 공개 키 (자물쇠). EC2에 넣는다.

```powershell
Get-Content apart-deploy.pub      # 한 줄이 나온다. 전체를 복사한다.
```

### 6-2. EC2에서
```bash
nano ~/.ssh/authorized_keys
```
**기존 줄은 지우지 말고**, 맨 아래 새 줄에 방금 복사한 한 줄을 붙여넣고 저장한다.

### 6-3. 내 PC에서 접속 확인
```powershell
ssh -i apart-deploy ubuntu@탄력적IP
```
접속되면 성공이다. `exit` 로 나온다.

## 7. GitHub Secrets 등록

저장소 페이지 > **Settings** > **Secrets and variables** > **Actions**.
`Secrets` 탭의 **New repository secret**, `Variables` 탭의 **New repository variable** 로 하나씩 추가한다.

### 백엔드 저장소 (Secret 3개)
| 종류 | 이름 | 값 |
|---|---|---|
| Secret | `EC2_HOST` | 탄력적 IP |
| Secret | `EC2_USER` | `ubuntu` |
| Secret | `EC2_SSH_KEY` | 개인 키 파일 `apart-deploy` 의 내용 전체 |

### 프론트 저장소 (Secret 4개 + Variable 1개)
| 종류 | 이름 | 값 |
|---|---|---|
| Secret | `EC2_HOST` | 위와 같음 |
| Secret | `EC2_USER` | 위와 같음 |
| Secret | `EC2_SSH_KEY` | 위와 같음 |
| Secret | `NEXT_PUBLIC_GOOGLE_MAPS_API_KEY` | 4단계의 **브라우저용** 키 |
| Variable | `NEXT_PUBLIC_API_URL` | `https://내도메인` (끝에 `/` 없이) |

`EC2_SSH_KEY` 넣는 법: PowerShell에서 `notepad apart-deploy` 로 열어서
`-----BEGIN OPENSSH PRIVATE KEY-----` 부터 `-----END OPENSSH PRIVATE KEY-----` 까지 **전부** 복사해 붙여넣는다.

두 저장소에 다 넣었으면 내 PC의 `apart-deploy`, `apart-deploy.pub` 파일은 지운다.

## 8. push 해서 배포하기

**백엔드를 먼저** 한다. (compose 파일을 EC2에 복사하는 것이 백엔드 쪽이다.)

```powershell
# 백엔드 폴더에서
git add .
git status          # application-local.yaml 이 목록에 없는지 확인
git commit -m "배포 설정"
git push
```

저장소의 **Actions** 탭에서 진행 상황을 본다. `test` → `deploy` 가 초록색이 되면 성공이다 (처음에는 5~10분).

그다음 프론트:

```powershell
# 프론트 폴더에서
git add .
git status          # .env.local 이 목록에 없는지 확인
git commit -m "배포 설정"
git push
```

둘 다 끝나면 EC2에서 확인:

```bash
cd ~/apart-together
docker compose ps                         # mysql, backend, frontend 3개가 Up
curl -s http://127.0.0.1:8080/api/config  # 백엔드가 JSON으로 답하면 정상
```

실패하면 Actions 탭에서 빨간 단계를 눌러 로그를 본다. 맨 아래 "막혔을 때" 표를 참고한다.

## 9. nginx + 인증서 (https)

EC2에서:

```bash
sudo apt update
sudo apt install -y nginx certbot python3-certbot-nginx

# 8단계에서 Actions가 복사해 둔 설정 파일을 nginx 폴더로 옮기고 도메인을 채운다
sudo cp ~/apart-together/apart-together.conf /etc/nginx/sites-available/apart-together
sudo sed -i 's/YOUR_DOMAIN/내도메인/g' /etc/nginx/sites-available/apart-together

sudo ln -s /etc/nginx/sites-available/apart-together /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

`nginx -t` 가 `syntax is ok` / `test is successful` 을 내면 된다.
이 시점에 `http://내도메인` 으로 접속하면 화면이 뜬다. (아직 http라 로그인은 되지 않는다. 다음 줄까지 해야 한다.)

```bash
sudo certbot --nginx -d 내도메인
```

- 이메일 입력 (만료 알림용) → 약관 동의 `Y` → 소식 메일은 `N` 이어도 된다.
- certbot이 인증서를 받고, 위 설정 파일에 443(https) 설정과 "http로 오면 https로 보내기"를 자동으로 추가한다.
- 인증서는 90일짜리이고 자동으로 갱신된다. 확인: `sudo certbot renew --dry-run`

## 10. 확인

`https://내도메인` 에서 순서대로 해 본다.

1. 회원가입 → 로그인
2. 방 만들기
3. 장소 추가 (구글맵 링크 붙여넣기) ← 서버용 키 확인
4. 지도 탭에서 핀이 보이는지 ← 브라우저용 키 확인
5. 채팅 (다른 브라우저로 한 명 더 들어가서) ← 실시간 연결(`/ws`) 확인

---

## 평소에는

코드를 고치고 `git push` 만 하면 된다. 진행 상황과 실패 로그는 저장소의 Actions 탭에 나온다.
배포하는 동안 해당 컨테이너는 수십 초 내려간다 (무중단 배포는 아니다).

## 비밀 값이 있는 곳

| 값 | 있는 곳 |
|---|---|
| 운영 MySQL 비밀번호, JWT 키, 서버용 구글 키 | EC2의 `~/apart-together/.env` (권한 600) |
| 배포용 SSH 개인 키, EC2 주소 | 두 저장소의 GitHub Secrets |
| 브라우저용 구글 키, 도메인 주소 | 프론트 저장소의 GitHub Secrets / Variables |
| 로컬 DB 비밀번호 등 | 내 PC의 `application-local.yaml`, `.env.local` |

git과 도커 이미지에는 비밀 값이 없다. GitHub Actions는 `.env` 를 읽지도 만들지도 않는다.
EC2에서 이미지를 받을 때 쓰는 토큰은 작업이 끝나면 만료되고 EC2에 남기지 않는다.

## 서버에서 쓰는 명령

```bash
cd ~/apart-together
docker compose ps                            # 상태
docker compose logs -f --tail 100 backend    # 백엔드 로그 (Ctrl+C 로 나옴)
docker compose logs -f --tail 100 frontend
docker compose up -d                         # .env 를 고친 뒤 반영
docker compose restart backend               # 그냥 다시 시작
sudo tail -n 50 /var/log/nginx/error.log     # nginx 오류
```

## 값을 바꿀 때

| 바꿀 것 | 하는 일 |
|---|---|
| 서버용 구글 키, JWT 키 | EC2에서 `nano .env` → `docker compose up -d` |
| 브라우저용 구글 키, 도메인 주소 | 프론트 저장소의 Secret / Variable 수정 → Actions 탭에서 `deploy-frontend` 를 **Run workflow** (다시 빌드해야 반영된다) |
| 테이블(컬럼 추가 등) | 아래 "알아 둘 것" 참고 |

## 알아 둘 것

- **테이블 SQL은 DB가 비어 있는 첫 실행 때만 돈다.** 나중에 컬럼을 추가하면 운영 DB에 직접 실행해야 한다.
  ```bash
  docker compose exec mysql mysql -uroot -p apart_together     # 비밀번호는 .env 의 MYSQL_ROOT_PASSWORD
  ```
- **DB 백업**: 데이터는 도커 볼륨(`apart-together_mysql-data`)에 있다. `docker compose down -v` 는 **데이터를 지운다.** (`-v` 없이 `down` 은 괜찮다.)
  ```bash
  docker compose exec mysql sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" apart_together' > backup-$(date +%F).sql
  ```

## 막혔을 때

| 증상 | 볼 곳 |
|---|---|
| Actions의 `SSH 준비` 나 복사 단계에서 실패 | `EC2_HOST` 가 탄력적 IP인지, 보안 그룹 22번이 열려 있는지, `EC2_SSH_KEY` 에 BEGIN~END 줄까지 다 들어갔는지, 6-2의 공개 키가 EC2에 들어갔는지 |
| `.env 가 없습니다` | 5-3 을 했는지. 위치가 `~/apart-together/.env` 인지 |
| `.env 에 OOO 가 필요합니다` | `.env` 의 그 항목이 비어 있다 |
| `permission denied ... docker.sock` | 5-1 을 하고 다시 접속했는지 |
| `백엔드가 뜨지 않았습니다` | 바로 아래에 찍힌 로그를 본다. DB 접속 오류면 `.env` 의 비밀번호를 처음 띄운 뒤에 바꾼 것은 아닌지 확인한다 |
| 이미지를 받지 못함 (`denied`, `not found`) | `.env` 의 이미지 주소가 소문자인지, 깃허브 아이디가 맞는지 |
| certbot 실패 | `nslookup 내도메인` 이 탄력적 IP를 내는지 (3단계), 보안 그룹 80번이 열려 있는지 |
| 사이트가 `502 Bad Gateway` | 컨테이너가 떠 있는지 `docker compose ps` |
| 로그인이 안 됨 (바로 풀림) | `https://` 로 접속했는지. `.env` 의 `DOMAIN` 과 실제 접속 주소가 같은지 |
| 지도 대신 안내 문구가 나옴 | 브라우저용 키의 Websites 에 `https://내도메인/*` 이 있는지, Maps JavaScript API 가 선택됐는지. 키를 바꿨으면 프론트를 다시 배포했는지 |
| 장소 추가가 안 됨 | 서버용 키의 IP addresses 에 탄력적 IP가 있는지, Places API (New) 가 선택됐는지. `docker compose logs --tail 50 backend` |
| 채팅이 실시간으로 안 옴 | nginx 설정에 `/ws` 부분이 있는지 (`sudo nginx -T \| grep -A3 "location /ws"`) |
