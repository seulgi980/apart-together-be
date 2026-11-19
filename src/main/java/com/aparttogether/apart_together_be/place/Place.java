package com.aparttogether.apart_together_be.place;

import java.math.BigDecimal;
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

/** 장소 카드 (F2) */
@Entity
@Table(name = "places")
@Getter
@Setter
@NoArgsConstructor
public class Place {

	public enum Visibility {
		SHARED, PRIVATE
	}

	public enum Category {
		RESTAURANT, CAFE, SIGHTSEEING, LODGING, SHOPPING, ETC
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "trip_id", nullable = false)
	private Long tripId;

	@Column(name = "created_by_user_id", nullable = false)
	private Long createdByUserId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Visibility visibility;

	@Column(nullable = false, length = 200)
	private String name;

	/** 영어 장소명. 없으면 null */
	@Column(name = "name_en", length = 200)
	private String nameEn;

	@Column(length = 300)
	private String address;

	/** 영어 주소. 없으면 null */
	@Column(name = "address_en", length = 300)
	private String addressEn;

	@Column(length = 50)
	private String area;

	@Column(precision = 9, scale = 6)
	private BigDecimal latitude;

	@Column(precision = 9, scale = 6)
	private BigDecimal longitude;

	@Column(name = "google_maps_url", nullable = false, length = 2048)
	private String googleMapsUrl;

	/** 같은 장소를 판단하는 기준 값 */
	@Column(name = "place_key", nullable = false, length = 200)
	private String placeKey;

	/** 중복 검사 범위: 0 = 공동 카드, 그 외 = 나만 카드 소유자 ID */
	@Column(name = "dedupe_owner_id", nullable = false)
	private Long dedupeOwnerId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Category category;

	@Column(length = 300)
	private String memo;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public boolean isShared() {
		return visibility == Visibility.SHARED;
	}

	/** 공동 카드는 모든 멤버가, 나만 카드는 등록자만 볼 수 있다 (F2-9) */
	public boolean isVisibleTo(Long userId) {
		return isShared() || createdByUserId.equals(userId);
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
