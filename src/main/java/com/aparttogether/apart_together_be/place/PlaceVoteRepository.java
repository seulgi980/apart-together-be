package com.aparttogether.apart_together_be.place;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlaceVoteRepository extends JpaRepository<PlaceVote, Long> {

	Optional<PlaceVote> findByPlaceIdAndUserId(Long placeId, Long userId);

	List<PlaceVote> findByPlaceIdIn(Collection<Long> placeIds);

	List<PlaceVote> findByPlaceId(Long placeId);

	/** 그 사용자가 이 여행방의 카드에 한 투표만 삭제한다 (F1-11) */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			delete from PlaceVote v
			where v.userId = :userId and v.placeId in (select p.id from Place p where p.tripId = :tripId)
			""")
	int deleteByUserInTrip(@Param("tripId") Long tripId, @Param("userId") Long userId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from PlaceVote v where v.placeId = :placeId and v.userId = :userId")
	int deleteByPlaceIdAndUserId(@Param("placeId") Long placeId, @Param("userId") Long userId);
}
