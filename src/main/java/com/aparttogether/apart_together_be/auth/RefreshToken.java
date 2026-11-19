package com.aparttogether.apart_together_be.auth;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 발급한 refresh token. 원문이 아니라 SHA-256 해시만 저장한다. */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "token_hash", nullable = false, length = 64)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public RefreshToken(Long userId, String tokenHash, Instant expiresAt, Instant createdAt) {
		this.userId = userId;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
		this.createdAt = createdAt;
	}
}
