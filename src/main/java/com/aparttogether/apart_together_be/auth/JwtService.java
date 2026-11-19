package com.aparttogether.apart_together_be.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import com.aparttogether.apart_together_be.config.AppProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** access token(JWT, HS256) 발급과 검증 */
@Component
public class JwtService {

	private final SecretKey key;
	private final AppProperties properties;

	public JwtService(AppProperties properties) {
		byte[] secret = properties.jwt().secret().getBytes(StandardCharsets.UTF_8);
		if (secret.length < 32) {
			throw new IllegalStateException("JWT_SECRET은 32바이트 이상이어야 합니다.");
		}
		this.key = Keys.hmacShaKeyFor(secret);
		this.properties = properties;
	}

	public String createAccessToken(Long userId) {
		Instant now = Instant.now();
		return Jwts.builder()
				.subject(String.valueOf(userId))
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(properties.jwt().accessTtl())))
				.signWith(key)
				.compact();
	}

	public long accessTtlSeconds() {
		return properties.jwt().accessTtl().toSeconds();
	}

	/** 서명과 만료를 확인하고 회원 ID를 돌려준다. 유효하지 않으면 빈 값. */
	public Optional<AuthUser> verify(String token) {
		if (token == null || token.isBlank()) {
			return Optional.empty();
		}
		try {
			Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
			return Optional.of(new AuthUser(Long.valueOf(claims.getSubject())));
		} catch (JwtException | IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/** "Bearer xxx" 헤더 값에서 토큰을 꺼내 확인한다 */
	public Optional<AuthUser> verifyBearer(String header) {
		if (header == null || !header.startsWith("Bearer ")) {
			return Optional.empty();
		}
		return verify(header.substring(7).trim());
	}
}
