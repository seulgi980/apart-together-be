package com.aparttogether.apart_together_be.schedule;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.aparttogether.apart_together_be.common.Times;

/**
 * 타임라인 규칙. 프론트 lib/domain/schedule.ts와 같은 계산이다.
 * (정렬, 시간 미정 항목의 자리값 다시 매기기, 시간 겹침, 틈에 들어가는 시간)
 */
public final class Timeline {

	private static final int BOTTOM = 1440;

	private Timeline() {
	}

	/**
	 * 화면에 보이는 순서 (schema.sql의 조회 정렬과 같다).
	 * 시간 항목은 시작 시각 순서. 시간 미정 항목은 anchor_minute 자리에 끼어 있고(같은 시각이면 시간 항목이 먼저),
	 * 같은 자리 안에서는 sort_order 순서다. anchor가 -1이면 맨 위, NULL이면 맨 아래.
	 */
	public static final Comparator<ScheduleItem> ORDER = Comparator
			.comparingInt((ScheduleItem item) -> item.isTimed() ? Times.minutes(item.getStartTime())
					: item.getAnchorMinute() == null ? BOTTOM : item.getAnchorMinute())
			.thenComparingInt(item -> item.isTimed() ? 0 : 1)
			.thenComparingInt(ScheduleItem::getSortOrder)
			.thenComparing(ScheduleItem::getId, Comparator.nullsLast(Comparator.naturalOrder()));

	public static List<ScheduleItem> sorted(Collection<ScheduleItem> dayItems) {
		List<ScheduleItem> list = new ArrayList<>(dayItems);
		list.sort(ORDER);
		return list;
	}

	/**
	 * 위에서부터 훑으며 시간 미정 항목의 자리값을 다시 매긴다.
	 *  - anchor_minute = 바로 앞 시간 항목의 시작 시각(분). 앞에 시간 항목이 없으면 -1.
	 *  - sort_order    = 같은 자리 안에서 0, 1, 2 ...
	 * 시간 항목은 그대로 둔다. 자리값이 실제로 바뀐 항목만 돌려준다.
	 */
	public static List<ScheduleItem> layout(List<ScheduleItem> sequence) {
		List<ScheduleItem> changed = new ArrayList<>();
		int anchor = ScheduleItem.ANCHOR_TOP;
		int order = 0;
		for (ScheduleItem item : sequence) {
			if (item.isTimed()) {
				anchor = Times.minutes(item.getStartTime());
				order = 0;
				continue;
			}
			if (!Objects.equals(item.getAnchorMinute(), anchor) || item.getSortOrder() != order) {
				item.setAnchorMinute(anchor);
				item.setSortOrder(order);
				changed.add(item);
			}
			order++;
		}
		return changed;
	}

	/** 받은 순서에서 시간 항목끼리는 start_time 순서여야 한다 */
	public static boolean isTimeOrdered(List<ScheduleItem> sequence) {
		int previous = -1;
		for (ScheduleItem item : sequence) {
			if (!item.isTimed()) {
				continue;
			}
			int start = Times.minutes(item.getStartTime());
			if (start < previous) {
				return false;
			}
			previous = start;
		}
		return true;
	}

	/**
	 * 시간 겹침 판단 (F4-10, F4-19).
	 *  - 종료시간이 없으면 시작 시각 한 점으로 본다.
	 *  - 한 점이 다른 항목의 범위(시작 이상, 종료 미만) 안에 들어오거나 시작 시각이 같으면 겹침.
	 *  - 맞닿는 경우(13:00~14:00과 14:00~15:00)는 겹침이 아니다.
	 */
	public static boolean overlaps(LocalTime aStart, LocalTime aEnd, LocalTime bStart, LocalTime bEnd) {
		int a = Times.minutes(aStart);
		int b = Times.minutes(bStart);
		if (a == b) {
			return true;
		}
		if (aEnd != null && bEnd != null) {
			return a < Times.minutes(bEnd) && b < Times.minutes(aEnd);
		}
		if (aEnd != null) {
			return b >= a && b < Times.minutes(aEnd);
		}
		if (bEnd != null) {
			return a >= b && a < Times.minutes(bEnd);
		}
		return false;
	}

	/** 같은 Day 안에서 겹치는 항목을 찾는다. 시간 미정 항목은 검사에서 제외한다. 없으면 null. */
	public static ScheduleItem findOverlap(Collection<ScheduleItem> dayItems, Long exceptId, LocalTime start,
			LocalTime end) {
		if (start == null) {
			return null;
		}
		for (ScheduleItem item : dayItems) {
			if (item.isTimed() && !Objects.equals(item.getId(), exceptId)
					&& overlaps(start, end, item.getStartTime(), item.getEndTime())) {
				return item;
			}
		}
		return null;
	}

	/**
	 * sequence의 index번째 자리(0 = 맨 위)에 시간을 정해 끼워 넣을 수 있는지.
	 * 앞 항목이 끝난 뒤(종료시간이 없는 앞 항목이면 그 시작 시각보다 뒤), 뒤 항목의 시작 전이어야 한다.
	 */
	public static boolean fitsGap(List<ScheduleItem> sequence, int index, LocalTime start, LocalTime end) {
		ScheduleItem previous = null;
		for (int i = index - 1; i >= 0; i--) {
			if (sequence.get(i).isTimed()) {
				previous = sequence.get(i);
				break;
			}
		}
		ScheduleItem next = null;
		for (int i = index; i < sequence.size(); i++) {
			if (sequence.get(i).isTimed()) {
				next = sequence.get(i);
				break;
			}
		}
		int startAt = Times.minutes(start);
		if (previous != null) {
			if (previous.getEndTime() != null ? startAt < Times.minutes(previous.getEndTime())
					: startAt <= Times.minutes(previous.getStartTime())) {
				return false;
			}
		}
		if (next != null) {
			int limit = Times.minutes(next.getStartTime());
			if (startAt >= limit || (end != null && Times.minutes(end) > limit)) {
				return false;
			}
		}
		return true;
	}

	/** "14:00~15:30" / "14:00" / "시간 미정" */
	public static String formatRange(LocalTime start, LocalTime end) {
		if (start == null) {
			return "시간 미정";
		}
		return end == null ? Times.format(start) : Times.format(start) + "~" + Times.format(end);
	}

	/** 조사 처리: 받침 유무에 따라 을/를 */
	public static String objectParticle(String word) {
		if (word == null || word.isEmpty()) {
			return "를";
		}
		char last = word.charAt(word.length() - 1);
		if (last < 0xAC00 || last > 0xD7A3) {
			return "를";
		}
		return (last - 0xAC00) % 28 == 0 ? "를" : "을";
	}
}
