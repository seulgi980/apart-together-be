package com.aparttogether.apart_together_be.place;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlaceRepository extends JpaRepository<Place, Long> {

	Optional<Place> findByIdAndTripId(Long id, Long tripId);

	/** 방의 카드 전체 (이름순) */
	List<Place> findByTripIdOrderByNameAscIdAsc(Long tripId);

	boolean existsByTripIdAndPlaceKey(Long tripId, String placeKey);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from Place p where p.id = :id")
	int deleteOne(@Param("id") Long id);
}
