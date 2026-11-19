package com.aparttogether.apart_together_be.schedule;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.aparttogether.apart_together_be.realtime.TripEvent;
import com.aparttogether.apart_together_be.realtime.TripEvents;

/**
 * 일정 변경 이벤트(SCHEDULE_CHANGED)를 만든다.
 * payload: { scope, ownerUserId, upserted: [항목...], removedIds: [항목 ID...] }
 */
@Component
public class ScheduleEvents {

	private final TripEvents events;
	private final ScheduleItemMapper mapper;

	public ScheduleEvents(TripEvents events, ScheduleItemMapper mapper) {
		this.events = events;
		this.mapper = mapper;
	}

	public void changed(Schedule schedule, Long actorUserId, Collection<ScheduleItem> upserted,
			Collection<Long> removedIds) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("scope", schedule.getScope().name());
		payload.put("ownerUserId", schedule.getOwnerUserId());
		payload.put("upserted", mapper.toResponses(schedule.getTripId(), upserted));
		payload.put("removedIds", List.copyOf(removedIds));
		events.publish(TripEvent.Type.SCHEDULE_CHANGED, schedule.getTripId(), actorUserId, payload);
	}

	/** 장소 카드가 지워져 함께 사라진 항목들을 일정별로 알린다 */
	public void removed(Collection<Schedule> tripSchedules, Long actorUserId, Collection<ScheduleItem> removedItems) {
		Map<Long, List<Long>> idsBySchedule = removedItems.stream().collect(Collectors.groupingBy(
				ScheduleItem::getScheduleId, Collectors.mapping(ScheduleItem::getId, Collectors.toList())));
		for (Schedule schedule : tripSchedules) {
			List<Long> ids = idsBySchedule.get(schedule.getId());
			if (ids != null) {
				changed(schedule, actorUserId, List.of(), ids);
			}
		}
	}

	/** 장소명/카테고리가 바뀌어 다시 그려야 하는 항목들을 일정별로 알린다 */
	public void refreshed(Collection<Schedule> tripSchedules, Long actorUserId, Collection<ScheduleItem> items) {
		Map<Long, List<ScheduleItem>> bySchedule = items.stream()
				.collect(Collectors.groupingBy(ScheduleItem::getScheduleId));
		for (Schedule schedule : tripSchedules) {
			List<ScheduleItem> changed = bySchedule.get(schedule.getId());
			if (changed != null) {
				changed(schedule, actorUserId, changed, List.of());
			}
		}
	}
}
