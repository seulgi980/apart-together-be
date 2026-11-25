package com.aparttogether.apart_together_be.schedule;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduleItemRepository extends JpaRepository<ScheduleItem, Long> {

	List<ScheduleItem> findByScheduleId(Long scheduleId);

	List<ScheduleItem> findByScheduleIdAndDayNo(Long scheduleId, int dayNo);

	List<ScheduleItem> findByPlaceId(Long placeId);

	@Query("select i from ScheduleItem i where i.scheduleId in (select s.id from Schedule s where s.tripId = :tripId)")
	List<ScheduleItem> findByTripId(@Param("tripId") Long tripId);

	/** 여행 기간을 벗어난 항목 (기간을 줄일 때) */
	@Query("""
			select i from ScheduleItem i
			where i.dayNo > :days and i.scheduleId in (select s.id from Schedule s where s.tripId = :tripId)
			""")
	List<ScheduleItem> findOutOfRange(@Param("tripId") Long tripId, @Param("days") int days);

	/** 항목이 속한 일정 ID만 읽는다 (항목 자체는 일정 행을 잠근 뒤에 읽어야 최신이다) */
	@Query("select i.scheduleId from ScheduleItem i where i.id = :id")
	Optional<Long> findScheduleIdById(@Param("id") Long id);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from ScheduleItem i where i.id in :ids")
	int deleteByIds(@Param("ids") Collection<Long> ids);
}
