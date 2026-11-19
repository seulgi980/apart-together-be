package com.aparttogether.apart_together_be.place;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlaceRepository extends JpaRepository<Place, Long> {

	Optional<Place> findByIdAndTripId(Long id, Long tripId);

	/** 내가 볼 수 있는 카드: 공동 카드 전체 + 내 나만 카드 (F2-9) */
	@Query("""
			select p from Place p
			where p.tripId = :tripId and (p.visibility = 'SHARED' or p.createdByUserId = :userId)
			order by p.name, p.id
			""")
	List<Place> findVisible(@Param("tripId") Long tripId, @Param("userId") Long userId);

	boolean existsByTripIdAndPlaceKeyAndDedupeOwnerId(Long tripId, String placeKey, Long dedupeOwnerId);

	@Query("select p.id from Place p where p.tripId = :tripId and p.visibility = 'PRIVATE' and p.createdByUserId = :userId")
	List<Long> findPrivateIdsOf(@Param("tripId") Long tripId, @Param("userId") Long userId);

	/** 나만 카드 삭제. 연결된 참고자료와 일정 항목은 FK CASCADE로 함께 지워진다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from Place p where p.tripId = :tripId and p.visibility = 'PRIVATE' and p.createdByUserId = :userId")
	int deletePrivateOf(@Param("tripId") Long tripId, @Param("userId") Long userId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from Place p where p.id = :id")
	int deleteOne(@Param("id") Long id);
}
