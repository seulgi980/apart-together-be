package com.aparttogether.apart_together_be.schedule;

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
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 일정 (F4-1, F7). 여행당 공동 일정 1개 + 멤버당 개인 일정 1개.
 * 이 행은 일정 변경을 한 줄로 세우는 잠금 대상(SELECT ... FOR UPDATE)이기도 하다.
 */
@Entity
@Table(name = "schedules")
@Getter
@Setter
@NoArgsConstructor
public class Schedule {

	public enum Scope {
		SHARED, PERSONAL
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "trip_id", nullable = false)
	private Long tripId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Scope scope;

	/** 개인 일정의 주인. 공동 일정은 NULL */
	@Column(name = "owner_user_id")
	private Long ownerUserId;

	/** 중복 검사 범위: 0 = 공동 일정, 그 외 = 개인 일정 주인 ID */
	@Column(name = "owner_key", nullable = false)
	private Long ownerKey;

	@Column(length = 300)
	private String memo;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public static Schedule shared(Long tripId) {
		Schedule schedule = new Schedule();
		schedule.tripId = tripId;
		schedule.scope = Scope.SHARED;
		schedule.ownerKey = 0L;
		return schedule;
	}

	public static Schedule personal(Long tripId, Long ownerUserId) {
		Schedule schedule = new Schedule();
		schedule.tripId = tripId;
		schedule.scope = Scope.PERSONAL;
		schedule.ownerUserId = ownerUserId;
		schedule.ownerKey = ownerUserId;
		return schedule;
	}

	public boolean isShared() {
		return scope == Scope.SHARED;
	}

	@PrePersist
	void onCreate() {
		createdAt = Times.now();
	}
}
