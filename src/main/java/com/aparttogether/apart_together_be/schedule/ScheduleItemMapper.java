package com.aparttogether.apart_together_be.schedule;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.aparttogether.apart_together_be.common.Times;
import com.aparttogether.apart_together_be.place.Place;
import com.aparttogether.apart_together_be.place.PlaceRepository;

/** 일정 항목 -> 응답 변환 */
@Component
public class ScheduleItemMapper {

	/**
	 * @param scope         SHARED(공동 일정) / PERSONAL(개인 일정)
	 * @param ownerUserId   개인 일정의 주인. 공동 일정은 null
	 * @param kind          PLACE(장소 항목) / MEMO(메모 항목)
	 * @param placeName     장소명. 남의 나만 카드여도 일정을 보는 멤버에게 보인다 (F2-9)
	 * @param startTime     "HH:mm". null이면 시간 미정
	 * @param anchorMinute  시간 미정 항목의 자리. -1 = 맨 위, null = 맨 아래
	 * @param lastChangedAt 하이라이트 기준 시각
	 */
	public record ScheduleItemResponse(
			Long id,
			Long tripId,
			Long scheduleId,
			String scope,
			Long ownerUserId,
			int dayNo,
			String kind,
			Long placeId,
			String placeName,
			String placeCategory,
			String title,
			String startTime,
			String endTime,
			String memo,
			Integer anchorMinute,
			int sortOrder,
			String lastChangeType,
			Long lastChangedByUserId,
			Instant lastChangedAt) {
	}

	private final ScheduleRepository schedules;
	private final PlaceRepository places;

	public ScheduleItemMapper(ScheduleRepository schedules, PlaceRepository places) {
		this.schedules = schedules;
		this.places = places;
	}

	public List<ScheduleItemResponse> toResponses(Long tripId, Collection<ScheduleItem> items) {
		if (items.isEmpty()) {
			return List.of();
		}
		Map<Long, Schedule> scheduleById = schedules.findByTripId(tripId).stream()
				.collect(Collectors.toMap(Schedule::getId, Function.identity()));
		Map<Long, Place> placeById = places
				.findAllById(items.stream().map(ScheduleItem::getPlaceId).filter(Objects::nonNull).distinct().toList())
				.stream().collect(Collectors.toMap(Place::getId, Function.identity()));
		return items.stream()
				.filter(item -> scheduleById.containsKey(item.getScheduleId()))
				.map(item -> toResponse(item, scheduleById.get(item.getScheduleId()),
						item.getPlaceId() == null ? null : placeById.get(item.getPlaceId())))
				.toList();
	}

	public ScheduleItemResponse toResponse(ScheduleItem item, Schedule schedule, Place place) {
		return new ScheduleItemResponse(
				item.getId(),
				schedule.getTripId(),
				schedule.getId(),
				schedule.getScope().name(),
				schedule.getOwnerUserId(),
				item.getDayNo(),
				item.isMemo() ? "MEMO" : "PLACE",
				item.getPlaceId(),
				place == null ? null : place.getName(),
				place == null ? null : place.getCategory().name(),
				item.getTitle(),
				Times.format(item.getStartTime()),
				Times.format(item.getEndTime()),
				item.getMemo(),
				item.getAnchorMinute(),
				item.getSortOrder(),
				item.getLastChangeType().name(),
				item.getLastChangedByUserId(),
				item.getLastChangedAt());
	}
}
