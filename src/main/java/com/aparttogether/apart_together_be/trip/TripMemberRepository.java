package com.aparttogether.apart_together_be.trip;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TripMemberRepository extends JpaRepository<TripMember, Long> {

	Optional<TripMember> findByTripIdAndUserId(Long tripId, Long userId);

	List<TripMember> findByTripIdOrderByJoinedAtAscIdAsc(Long tripId);

	List<TripMember> findByTripIdInOrderByJoinedAtAscIdAsc(Collection<Long> tripIds);

	@Query("select count(m) from TripMember m where m.tripId = :tripId and m.status = 'ACTIVE'")
	int countActive(@Param("tripId") Long tripId);

	@Query("select m.tripId from TripMember m where m.userId = :userId and m.status = 'ACTIVE'")
	List<Long> findActiveTripIdsOf(@Param("userId") Long userId);
}
