package com.aparttogether.apart_together_be.schedule;

import java.time.Instant;
import java.time.LocalTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

/**
 * 일정 항목 (F4). place_id가 NULL이면 장소 없는 메모 항목이다.
 * start_time이 NULL이면 시간 미정이고, 타임라인 안의 자리는 anchor_minute/sort_order가 기억한다.
 */
@Entity
@Table(name = "schedule_items")
@Getter
@Setter
@NoArgsConstructor
public class ScheduleItem {

	public enum ChangeType {
		ADDED, MOVED, TIME_CHANGED, EDITED
	}

	/** anchor_minute: 맨 위(첫 시간 항목보다 앞) */
	public static final int ANCHOR_TOP = -1;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "schedule_id", nullable = false)
	private Long scheduleId;

	@Column(name = "day_no", nullable = false)
	private int dayNo;

	@Column(name = "place_id")
	private Long placeId;

	/** places.place_key 복사본. (schedule_id, place_key, place_slot) 유니크가 중복 배치를 막는다. */
	@Column(name = "place_key", length = 200)
	private String placeKey;

	/**
	 * 같은 장소를 이 일정에 몇 번째로 넣었는지 (1부터). 숙소가 아니면 항상 1.
	 * 숙소는 체크인/체크아웃 때문에 2까지 쓴다. 메모 항목은 기본값 1.
	 */
	@JdbcTypeCode(SqlTypes.TINYINT)
	@Column(name = "place_slot", nullable = false)
	private int placeSlot = 1;

	@Column(length = 30)
	private String title;

	@Column(length = 300)
	private String memo;

	/** 현지 시각 그대로 저장한다. 시간대 변환을 타지 않게 LocalTime을 드라이버에 그대로 넘긴다. */
	@JdbcTypeCode(SqlTypes.LOCAL_TIME)
	@Column(name = "start_time")
	private LocalTime startTime;

	@JdbcTypeCode(SqlTypes.LOCAL_TIME)
	@Column(name = "end_time")
	private LocalTime endTime;

	/** 시간 미정 항목의 자리: 바로 앞 시간 항목의 시작 시각(분). -1 = 맨 위, NULL = 맨 아래 */
	@JdbcTypeCode(SqlTypes.SMALLINT)
	@Column(name = "anchor_minute")
	private Integer anchorMinute;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@Enumerated(EnumType.STRING)
	@Column(name = "last_change_type", nullable = false)
	private ChangeType lastChangeType;

	@Column(name = "last_changed_by_user_id", nullable = false)
	private Long lastChangedByUserId;

	/** 하이라이트 기준 시각. 사용자가 직접 바꿨을 때만 갱신한다. */
	@Column(name = "last_changed_at", nullable = false)
	private Instant lastChangedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public boolean isTimed() {
		return startTime != null;
	}

	public boolean isMemo() {
		return placeId == null;
	}

	/** 사용자가 직접 바꿨을 때만 부른다 (하이라이트) */
	public void markChanged(ChangeType type, Long userId) {
		this.lastChangeType = type;
		this.lastChangedByUserId = userId;
		this.lastChangedAt = Times.now();
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
