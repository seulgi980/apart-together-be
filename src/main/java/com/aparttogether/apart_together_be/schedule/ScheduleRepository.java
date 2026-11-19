package com.aparttogether.apart_together_be.schedule;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

	List<Schedule> findByTripId(Long tripId);

	Optional<Schedule> findByTripIdAndOwnerKey(Long tripId, Long ownerKey);

	/**
	 * 일정 행을 SELECT ... FOR UPDATE로 잠근다.
	 * 같은 일정을 바꾸는 요청은 이 잠금으로 한 줄로 서고, 먼저 저장된 요청이 우선한다. (F4-18)
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Schedule s where s.tripId = :tripId and s.ownerKey = :ownerKey")
	Optional<Schedule> findForUpdate(@Param("tripId") Long tripId, @Param("ownerKey") Long ownerKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Schedule s where s.id = :id")
	Optional<Schedule> findForUpdateById(@Param("id") Long id);

	/** 개인 일정 삭제. 안의 항목은 FK CASCADE로 함께 지워진다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from Schedule s where s.tripId = :tripId and s.ownerKey = :ownerKey")
	int deleteByTripIdAndOwnerKey(@Param("tripId") Long tripId, @Param("ownerKey") Long ownerKey);
}
