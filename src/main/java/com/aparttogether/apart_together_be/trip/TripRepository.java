package com.aparttogether.apart_together_be.trip;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface TripRepository extends JpaRepository<Trip, Long> {

	Optional<Trip> findByInviteCode(String inviteCode);

	boolean existsByInviteCode(String inviteCode);

	/** 멤버 변동(참여, 나가기, 내보내기, 핀 색 변경)을 한 줄로 세우기 위한 잠금 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Trip t where t.id = :id")
	Optional<Trip> findForUpdate(@Param("id") Long id);

	@Query("""
			select t from Trip t, TripMember m
			where m.tripId = t.id and m.userId = :userId and m.status = 'ACTIVE'
			order by t.startDate, t.id
			""")
	List<Trip> findActiveTripsOf(@Param("userId") Long userId);
}
