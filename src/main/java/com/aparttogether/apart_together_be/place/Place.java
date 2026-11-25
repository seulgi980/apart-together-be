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

/** 장소 카드 (F2). 모든 카드는 방의 멤버 모두가 함께 쓴다. 방마다 같은 장소(place_key)는 1장이다. */
@Entity
@Table(name = "places")
@Getter
@Setter
@NoArgsConstructor
public class Place {

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

	/** 영어 지역명. 없으면 null */
	@Column(name = "area_en", length = 50)
	private String areaEn;

	@Column(precision = 9, scale = 6)
	private BigDecimal latitude;

	@Column(precision = 9, scale = 6)
	private BigDecimal longitude;

	@Column(name = "google_maps_url", nullable = false, length = 2048)
	private String googleMapsUrl;

	/** 같은 장소를 판단하는 기준 값. (trip_id, place_key) 유니크로 방마다 1장 */
	@Column(name = "place_key", nullable = false, length = 200)
	private String placeKey;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Category category;

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
