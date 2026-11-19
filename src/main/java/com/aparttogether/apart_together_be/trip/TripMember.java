package com.aparttogether.apart_together_be.trip;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 여행방 멤버 (F1-2, F1-5, F1-11, F6-4).
 * 나가거나 내보내져도 행은 남기고 status만 바꾼다.
 */
@Entity
@Table(name = "trip_members")
@Getter
@Setter
@NoArgsConstructor
public class TripMember {

	public enum Status {
		ACTIVE, LEFT, KICKED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "trip_id", nullable = false)
	private Long tripId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	/** 핀 색 번호 (1부터). ACTIVE가 아니면 NULL */
	@JdbcTypeCode(SqlTypes.TINYINT)
	@Column(name = "pin_color_no")
	private Integer pinColorNo;

	@Column(name = "last_read_chat_id")
	private Long lastReadChatId;

	@Column(name = "joined_at", nullable = false)
	private Instant joinedAt;

	@Column(name = "left_at")
	private Instant leftAt;

	public boolean isActive() {
		return status == Status.ACTIVE;
	}
}
