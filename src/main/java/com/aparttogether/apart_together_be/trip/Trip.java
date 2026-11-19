package com.aparttogether.apart_together_be.trip;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

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

/** 여행방 (F1-4, F1-8, F1-10) */
@Entity
@Table(name = "trips")
@Getter
@Setter
@NoArgsConstructor
public class Trip {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "owner_user_id", nullable = false)
	private Long ownerUserId;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	@Column(name = "end_date", nullable = false)
	private LocalDate endDate;

	@Column(name = "invite_code", nullable = false, length = 32)
	private String inviteCode;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/** Day 1 ~ Day N의 N */
	public int dayCount() {
		return (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
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
