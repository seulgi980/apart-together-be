package com.aparttogether.apart_together_be.place;

import java.time.Instant;

import com.aparttogether.apart_together_be.common.Times;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 취향 투표 (F3). 공동 카드에만 있다. */
@Entity
@Table(name = "place_votes")
@Getter
@Setter
@NoArgsConstructor
public class PlaceVote {

	public enum VoteType {
		LIKE, PASS
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "place_id", nullable = false)
	private Long placeId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "vote_type", nullable = false)
	private VoteType voteType;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

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
