package com.aparttogether.apart_together_be.user;

import java.time.Instant;

import com.aparttogether.apart_together_be.common.Times;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 회원 (F1) */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "login_id", nullable = false, length = 50)
	private String loginId;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(nullable = false, length = 50)
	private String nickname;

	@Column(name = "profile_image_url", length = 500)
	private String profileImageUrl;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public User(String loginId, String passwordHash, String nickname) {
		this.loginId = loginId;
		this.passwordHash = passwordHash;
		this.nickname = nickname;
	}

	@PrePersist
	void onCreate() {
		createdAt = Times.now();
		updatedAt = createdAt;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Times.now();
	}
}
