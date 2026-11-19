package com.aparttogether.apart_together_be.auth;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aparttogether.apart_together_be.config.AppProperties;
import com.aparttogether.apart_together_be.user.UserDtos.UserResponse;
import com.aparttogether.apart_together_be.user.UserRepository;
import com.aparttogether.apart_together_be.user.UserRules;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	public record SignupRequest(
			@NotBlank @Pattern(regexp = UserRules.LOGIN_ID, message = UserRules.LOGIN_ID_HELP) String loginId,
			@NotBlank @Pattern(regexp = UserRules.PASSWORD, message = UserRules.PASSWORD_HELP) String password,
			@NotBlank @Pattern(regexp = UserRules.NICKNAME, message = UserRules.NICKNAME_HELP) String nickname) {
	}

	public record LoginRequest(@NotBlank String loginId, @NotBlank String password) {
	}

	/** access token은 응답 본문으로만 내려준다 (프론트가 메모리에 둔다) */
	public record AuthResponse(String accessToken, long expiresIn, UserResponse user) {
	}

	private final AuthService authService;
	private final JwtService jwtService;
	private final UserRepository users;
	private final AppProperties.RefreshCookie cookie;
	private final Duration refreshTtl;

	public AuthController(AuthService authService, JwtService jwtService, UserRepository users,
			AppProperties properties) {
		this.authService = authService;
		this.jwtService = jwtService;
		this.users = users;
		this.cookie = properties.auth().refreshCookie();
		this.refreshTtl = properties.jwt().refreshTtl();
	}

	@PostMapping("/signup")
	public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
		return respond(HttpStatus.CREATED,
				authService.signup(request.loginId(), request.password(), request.nickname()));
	}

	@PostMapping("/login")
	public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
		return respond(HttpStatus.OK, authService.login(request.loginId(), request.password()));
	}

	/** 가입 화면의 중복 확인 */
	@GetMapping("/availability")
	public Map<String, Boolean> availability(@RequestParam(required = false) String loginId,
			@RequestParam(required = false) String nickname) {
		boolean available = true;
		if (loginId != null) {
			available = !users.existsByLoginId(loginId);
		}
		if (nickname != null) {
			available = available && !users.existsByNickname(nickname);
		}
		return Map.of("available", available);
	}

	/** 재발급. POST만 받는다. refresh token 쿠키로 새 access token과 새 refresh token을 발급한다. */
	@PostMapping("/token/refresh")
	public ResponseEntity<AuthResponse> refresh(
			@CookieValue(name = "${app.auth.refresh-cookie.name}", required = false) String refreshToken) {
		return respond(HttpStatus.OK, authService.refresh(refreshToken));
	}

	/** 로그아웃. 저장된 refresh token을 무효로 하고 쿠키를 비운다. */
	@PostMapping("/token/logout")
	public ResponseEntity<Void> logout(
			@CookieValue(name = "${app.auth.refresh-cookie.name}", required = false) String refreshToken) {
		authService.logout(refreshToken);
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
				.build();
	}

	private ResponseEntity<AuthResponse> respond(HttpStatus status, AuthService.Tokens tokens) {
		return ResponseEntity.status(status)
				.header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken(), refreshTtl).toString())
				.body(new AuthResponse(tokens.accessToken(), jwtService.accessTtlSeconds(),
						UserResponse.of(tokens.user())));
	}

	private ResponseCookie refreshCookie(String value, Duration maxAge) {
		return ResponseCookie.from(cookie.name(), value)
				.httpOnly(true)
				.secure(cookie.secure())
				.sameSite(cookie.sameSite())
				.path(cookie.path())
				.maxAge(maxAge)
				.build();
	}
}
