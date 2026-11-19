package com.aparttogether.apart_together_be.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aparttogether.apart_together_be.support.IntegrationTest;
import com.aparttogether.apart_together_be.support.TestApi;

class AuthTest extends IntegrationTest {

	private TestApi.Response refresh(String cookie) {
		return api.send("POST", null, "/api/auth/token/refresh", null,
				cookie == null ? null : Map.of("Cookie", "refresh_token=" + cookie));
	}

	@Test
	@DisplayName("가입하면 access token은 본문으로, refresh token은 HttpOnly·Secure·SameSite=Lax 쿠키(재발급 경로 한정)로 내려온다")
	void signup_issuesTokens() {
		TestApi.Response response = api.post(null, "/api/auth/signup",
				TestApi.json("loginId", "jieun_01", "password", "password1!", "nickname", "지은"));

		assertThat(response.status()).isEqualTo(201);
		assertThat(response.body().get("accessToken").asString()).isNotBlank();
		assertThat(response.body().get("expiresIn").asLong()).isEqualTo(900);
		assertThat(response.body().get("user").get("nickname").asString()).isEqualTo("지은");
		assertThat(response.body().toString()).doesNotContain("password");

		String cookie = response.setCookie("refresh_token");
		assertThat(cookie).contains("HttpOnly").contains("Secure").contains("SameSite=Lax")
				.contains("Path=/api/auth/token").contains("Max-Age=1209600");
	}

	@Test
	@DisplayName("비밀번호는 BCrypt 해시로, refresh token은 SHA-256 해시로 저장한다 (원문 저장 안 함)")
	void secretsAreStoredHashed() {
		Member member = signup("지은");

		String passwordHash = jdbc.queryForObject("select password_hash from users where id = ?", String.class,
				member.id());
		assertThat(passwordHash).startsWith("$2").doesNotContain("password1!");

		String tokenHash = jdbc.queryForObject("select token_hash from refresh_tokens where user_id = ?",
				String.class, member.id());
		assertThat(tokenHash).hasSize(64).isNotEqualTo(member.refreshCookie());
		assertThat(tokenHash).isEqualTo(AuthService.hash(member.refreshCookie()));
	}

	@Test
	@DisplayName("아이디·닉네임 중복과 형식 오류는 가입을 거절한다")
	void signup_validation() {
		Member member = signup("지은");

		assertThat(code(api.post(null, "/api/auth/signup",
				TestApi.json("loginId", member.loginId(), "password", "password1!", "nickname", "다른이름"))))
				.isEqualTo("LOGIN_ID_TAKEN");
		assertThat(code(api.post(null, "/api/auth/signup",
				TestApi.json("loginId", "another_id", "password", "password1!", "nickname", "지은"))))
				.isEqualTo("NICKNAME_TAKEN");
		assertThat(code(api.post(null, "/api/auth/signup",
				TestApi.json("loginId", "AB", "password", "password1!", "nickname", "민수"))))
				.isEqualTo("VALIDATION_FAILED");
		assertThat(code(api.post(null, "/api/auth/signup",
				TestApi.json("loginId", "minsu_01", "password", "short", "nickname", "민수"))))
				.isEqualTo("VALIDATION_FAILED");

		assertThat(api.get(null, "/api/auth/availability?loginId=" + member.loginId()).body().get("available")
				.asBoolean()).isFalse();
		assertThat(api.get(null, "/api/auth/availability?loginId=free_id").body().get("available").asBoolean())
				.isTrue();
	}

	@Test
	@DisplayName("로그인: 비밀번호가 틀리면 INVALID_CREDENTIALS, 맞으면 토큰을 발급한다")
	void login() {
		Member member = signup("지은");

		assertThat(code(api.post(null, "/api/auth/login",
				TestApi.json("loginId", member.loginId(), "password", "wrong-password")))).isEqualTo("INVALID_CREDENTIALS");

		TestApi.Response ok = api.post(null, "/api/auth/login",
				TestApi.json("loginId", member.loginId(), "password", "password1!"));
		assertThat(ok.status()).isEqualTo(200);
		assertThat(api.get(ok.body().get("accessToken").asString(), "/api/users/me").body().get("id").asLong())
				.isEqualTo(member.id());
	}

	@Test
	@DisplayName("기능 API는 헤더의 access token으로만 인증한다. refresh 쿠키만으로는 부를 수 없다")
	void apiRequiresBearerToken() {
		Member member = signup("지은");

		assertThat(api.get(null, "/api/users/me").status()).isEqualTo(401);
		assertThat(api.get("not-a-token", "/api/users/me").status()).isEqualTo(401);
		TestApi.Response cookieOnly = api.send("GET", null, "/api/users/me", null,
				Map.of("Cookie", "refresh_token=" + member.refreshCookie()));
		assertThat(cookieOnly.status()).isEqualTo(401);
		assertThat(code(cookieOnly)).isEqualTo("UNAUTHORIZED");
	}

	@Test
	@DisplayName("재발급은 POST만 받고, 할 때마다 refresh token을 새로 발급하며 이전 것은 무효로 한다(회전)")
	void refresh_rotates() {
		Member member = signup("지은");

		assertThat(api.send("GET", null, "/api/auth/token/refresh", null,
				Map.of("Cookie", "refresh_token=" + member.refreshCookie())).status()).isEqualTo(405);

		TestApi.Response first = refresh(member.refreshCookie());
		assertThat(first.status()).isEqualTo(200);
		String rotated = first.cookie("refresh_token");
		assertThat(rotated).isNotBlank().isNotEqualTo(member.refreshCookie());
		assertThat(api.get(first.body().get("accessToken").asString(), "/api/users/me").status()).isEqualTo(200);

		assertThat(refresh(rotated).status()).isEqualTo(200);
		assertThat(refresh(null).status()).isEqualTo(401);
		assertThat(refresh("unknown-token").status()).isEqualTo(401);
	}

	@Test
	@DisplayName("이미 무효가 된 refresh token이 다시 들어오면 그 사용자의 refresh token을 전부 무효로 한다")
	void refresh_reuseRevokesAll() {
		Member member = signup("지은");
		String rotated = refresh(member.refreshCookie()).cookie("refresh_token");

		// 이미 쓴(무효가 된) 토큰을 다시 사용
		TestApi.Response reused = refresh(member.refreshCookie());
		assertThat(reused.status()).isEqualTo(401);
		assertThat(code(reused)).isEqualTo("INVALID_REFRESH_TOKEN");

		// 정상적으로 회전된 최신 토큰까지 함께 무효가 된다
		assertThat(refresh(rotated).status()).isEqualTo(401);
		Integer active = jdbc.queryForObject(
				"select count(*) from refresh_tokens where user_id = ? and revoked_at is null", Integer.class,
				member.id());
		assertThat(active).isZero();
	}

	@Test
	@DisplayName("로그아웃하면 저장된 refresh token을 무효로 하고 쿠키를 비운다")
	void logout() {
		Member member = signup("지은");

		TestApi.Response response = api.send("POST", null, "/api/auth/token/logout", null,
				Map.of("Cookie", "refresh_token=" + member.refreshCookie()));
		assertThat(response.status()).isEqualTo(204);
		assertThat(response.setCookie("refresh_token")).contains("Max-Age=0").contains("Path=/api/auth/token");
		assertThat(refresh(member.refreshCookie()).status()).isEqualTo(401);
	}

	@Test
	@DisplayName("CORS: 설정한 프론트 주소만 허용하고 자격 증명을 허용한다. 다른 주소는 되돌려주지 않는다")
	void cors() {
		TestApi.Response allowed = api.send("OPTIONS", null, "/api/auth/login", null,
				Map.of("Origin", "http://localhost:3000", "Access-Control-Request-Method", "POST"));
		assertThat(allowed.headers().get("access-control-allow-origin")).containsExactly("http://localhost:3000");
		assertThat(allowed.headers().get("access-control-allow-credentials")).containsExactly("true");

		TestApi.Response denied = api.send("OPTIONS", null, "/api/auth/login", null,
				Map.of("Origin", "http://evil.example.com", "Access-Control-Request-Method", "POST"));
		assertThat(denied.status()).isEqualTo(403);
		assertThat(denied.headers()).doesNotContainKey("access-control-allow-origin");
	}

	@Test
	@DisplayName("프로필: 닉네임을 바꿀 수 있고, 다른 사람이 쓰는 닉네임으로는 바꿀 수 없다")
	void profile() {
		Member jieun = signup("지은");
		signup("민수");

		TestApi.Response updated = api.patch(jieun.token(), "/api/users/me", TestApi.json("nickname", "지은이"));
		assertThat(updated.status()).isEqualTo(200);
		assertThat(api.get(jieun.token(), "/api/users/me").body().get("nickname").asString()).isEqualTo("지은이");

		assertThat(code(api.patch(jieun.token(), "/api/users/me", TestApi.json("nickname", "민수"))))
				.isEqualTo("NICKNAME_TAKEN");
		assertThat(code(api.patch(jieun.token(), "/api/users/me",
				TestApi.json("nickname", "지은이", "profileImageUrl", "https://evil.example.com/x.png"))))
				.isEqualTo("INVALID_IMAGE");
	}
}
