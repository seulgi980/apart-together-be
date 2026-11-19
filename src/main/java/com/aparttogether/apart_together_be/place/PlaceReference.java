package com.aparttogether.apart_together_be.place;

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

/** 참고자료 (F2-4, F2-5) */
@Entity
@Table(name = "place_references")
@Getter
@Setter
@NoArgsConstructor
public class PlaceReference {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "place_id", nullable = false)
	private Long placeId;

	@Column(name = "added_by_user_id", nullable = false)
	private Long addedByUserId;

	@Column(nullable = false, length = 2048)
	private String url;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(length = 300)
	private String memo;

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
