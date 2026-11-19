package com.aparttogether.apart_together_be.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aparttogether.apart_together_be.common.ApiException;
import com.aparttogether.apart_together_be.common.ErrorCode;
import com.aparttogether.apart_together_be.common.Times;
import com.aparttogether.apart_together_be.config.AppProperties;
import com.aparttogether.apart_together_be.user.User;
import com.aparttogether.apart_together_be.user.UserRepository;

@Service
public class AuthService {

	/** 발급 결과. refreshToken은 원문이며 쿠키로만 내려간다. */
	public record Tokens(User user, String accessToken, String refreshToken) {
	}

	private final UserRepository users;
	private final RefreshTokenRepository refreshTokens;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final AppProperties properties;
	private final SecureRandom random = new SecureRandom();

	public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
			JwtService jwtService, AppProperties properties) {
		this.users = users;
		this.refreshTokens = refreshTokens;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.properties = properties;
	}

	@Transactional
	public Tokens signup(String loginId, String password, String nickname) {
		if (users.existsByLoginId(loginId)) {
			throw new ApiException(ErrorCode.LOGIN_ID_TAKEN);
		}
		if (users.existsByNickname(nickname)) {
			throw new ApiException(ErrorCode.NICKNAME_TAKEN);
		}
		User user;
		try {
			user = users.saveAndFlush(new User(loginId, passwordEncoder.encode(password), nickname));
		} catch (DataIntegrityViolationException e) {
			// 동시에 같은 값으로 가입한 경우: 유니크 제약이 막는다
			String message = String.valueOf(e.getMostSpecificCause().getMessage());
			throw new ApiException(
					message.contains("uq_users_nickname") ? ErrorCode.NICKNAME_TAKEN : ErrorCode.LOGIN_ID_TAKEN);
		}
		return issue(user);
	}

	@Transactional
	public Tokens login(String loginId, String password) {
		User user = users.findByLoginId(loginId).orElse(null);
		if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
			throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
		}
		return issue(user);
	}

	/**
	 * 재발급(회전). refresh token도 새로 발급하고 이전 것은 무효로 한다.
	 * 이미 무효가 된 토큰이 다시 들어오면 그 회원의 토큰을 전부 무효로 한다.
	 * (거절하면서도 무효 처리는 저장돼야 하므로 ApiException에는 롤백하지 않는다)
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public Tokens refresh(String rawRefreshToken) {
		if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
			throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
		}
		RefreshToken stored = refreshTokens.findByTokenHash(hash(rawRefreshToken))
				.orElseThrow(() -> new ApiException(ErrorCode.INVALID_REFRESH_TOKEN));
		Instant now = Times.now();
		Long userId = stored.getUserId();
		// 유효한 상태일 때만 무효로 바꾼다. 0건이면 이미 쓰인 토큰이 다시 온 것이다(재사용).
		if (stored.getRevokedAt() != null || refreshTokens.revokeIfActive(stored.getId(), now) == 0) {
			refreshTokens.revokeAllOfUser(userId, now);
			throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
		}
		if (!stored.getExpiresAt().isAfter(now)) {
			throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
		}
		User user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.INVALID_REFRESH_TOKEN));
		return issue(user);
	}

	@Transactional
	public void logout(String rawRefreshToken) {
		if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
			return;
		}
		refreshTokens.findByTokenHash(hash(rawRefreshToken))
				.ifPresent(token -> refreshTokens.revokeIfActive(token.getId(), Times.now()));
	}

	private Tokens issue(User user) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant now = Times.now();
		refreshTokens.save(new RefreshToken(user.getId(), hash(raw), now.plus(properties.jwt().refreshTtl()), now));
		return new Tokens(user, jwtService.createAccessToken(user.getId()), raw);
	}

	static String hash(String raw) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
