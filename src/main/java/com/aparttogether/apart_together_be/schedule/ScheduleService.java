package com.aparttogether.apart_together_be.schedule;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aparttogether.apart_together_be.chat.ChatService;
import com.aparttogether.apart_together_be.common.ApiException;
import com.aparttogether.apart_together_be.common.ErrorCode;
import com.aparttogether.apart_together_be.common.Times;
import com.aparttogether.apart_together_be.place.Place;
import com.aparttogether.apart_together_be.place.PlaceRepository;
import com.aparttogether.apart_together_be.schedule.ScheduleItemMapper.ScheduleItemResponse;
import com.aparttogether.apart_together_be.trip.MembershipGuard;
import com.aparttogether.apart_together_be.trip.Trip;
import com.aparttogether.apart_together_be.trip.TripRepository;
import com.aparttogether.apart_together_be.user.UserRepository;

/**
 * 일정 (F4). constraints.sql 3부의 [일정 검증], [타임라인 순서], [Day 이동], [하이라이트] 규칙을 그대로 따른다.
 * 모든 변경은 일정(schedules) 행을 SELECT ... FOR UPDATE로 잠근 트랜잭션 안에서 검증하고 저장한다.
 * 같은 일정을 동시에 바꾸는 요청은 한 줄로 서고, 먼저 저장된 요청이 우선한다. (F4-18)
 */
@Service
public class ScheduleService {

	private static final int MAX_TITLE_LENGTH = 30;
	private static final int MAX_MEMO_LENGTH = 300;

	/**
	 * 일정에 항목 추가.
	 *
	 * @param placeId   장소 항목이면 장소 카드 ID. 없으면 메모 항목(title 필수)
	 * @param startTime "HH:mm". 없으면 시간 미정
	 * @param index     타임라인의 이 자리(0 = 맨 위)에 끼워 넣는다. 없으면 그 Day의 맨 아래 시간 미정으로([+] 버튼)
	 */
	public record AddItemRequest(Integer dayNo, Long placeId, String title, String startTime, String endTime,
			String memo, Integer index) {
	}

	private final ScheduleRepository schedules;
	private final ScheduleItemRepository items;
	private final PlaceRepository places;
	private final TripRepository trips;
	private final UserRepository users;
	private final MembershipGuard guard;
	private final ScheduleItemMapper mapper;
	private final ScheduleEvents scheduleEvents;
	private final ChatService chat;

	public ScheduleService(ScheduleRepository schedules, ScheduleItemRepository items, PlaceRepository places,
			TripRepository trips, UserRepository users, MembershipGuard guard, ScheduleItemMapper mapper,
			ScheduleEvents scheduleEvents, ChatService chat) {
		this.schedules = schedules;
		this.items = items;
		this.places = places;
		this.trips = trips;
		this.users = users;
		this.guard = guard;
		this.mapper = mapper;
		this.scheduleEvents = scheduleEvents;
		this.chat = chat;
	}

	/* ───────── 조회 ───────── */

	/**
	 * 방의 모든 일정 항목 (공동 + 모든 멤버의 개인 일정).
	 * 다른 멤버의 개인 일정은 읽기 전용으로 볼 수 있고, 나만 카드로 넣은 항목도 장소명/카테고리는 보인다.
	 */
	@Transactional(readOnly = true)
	public List<ScheduleItemResponse> list(Long userId, Long tripId) {
		guard.requireActive(tripId, userId);
		return mapper.toResponses(tripId, items.findByTripId(tripId));
	}

	/* ───────── 배치 (추가) ───────── */

	@Transactional
	public ScheduleItemResponse addItem(Long userId, Long tripId, String owner, AddItemRequest request) {
		guard.requireActive(tripId, userId);
		Schedule schedule = lockWritable(userId, tripId, owner);
		int day = requireDay(tripId, request.dayNo());
		LocalTime start = Times.parse(request.startTime());
		LocalTime end = Times.parse(request.endTime());
		requireValidRange(start, end);

		ScheduleItem item = new ScheduleItem();
		item.setScheduleId(schedule.getId());
		item.setDayNo(day);
		item.setStartTime(start);
		item.setEndTime(end);
		item.setMemo(normalizeMemo(request.memo()));

		Place place = null;
		if (request.placeId() != null) {
			// [권한] 나만 카드는 등록자 본인만 쓸 수 있다
			place = places.findByIdAndTripId(request.placeId(), tripId)
					.filter(found -> found.isVisibleTo(userId))
					.orElseThrow(() -> new ApiException(ErrorCode.PLACE_NOT_FOUND));
			// 공동 일정에는 공동 카드만 넣을 수 있다. 나만 카드는 그 카드 주인의 개인 일정에만.
			if (schedule.isShared() && !place.isShared()) {
				throw new ApiException(ErrorCode.SCHEDULE_PLACE_NOT_ALLOWED);
			}
			// 중복 배치 검증: 기준은 카드가 아니라 장소(place_key)다 (F4-20)
			// 숙소는 체크인/체크아웃 때문에 2번까지, 나머지는 1번만 넣을 수 있다.
			List<ScheduleItem> placed = items.findByScheduleIdAndPlaceKey(schedule.getId(), place.getPlaceKey());
			int limit = maxPlacements(place);
			if (placed.size() >= limit) {
				throw conflict(ErrorCode.SCHEDULE_DUPLICATE, placed.get(0));
			}
			item.setPlaceId(place.getId());
			// 일정 항목을 만들 때 place_key는 places.place_key를 복사해서 넣는다
			item.setPlaceKey(place.getPlaceKey());
			// 비어 있는 가장 작은 번호(1, 2 ...)를 쓴다. 1번을 지웠다면 다음 배치가 1번을 다시 쓴다.
			item.setPlaceSlot(freeSlot(placed, limit));
		} else {
			item.setTitle(requireTitle(request.title()));
		}

		List<ScheduleItem> dayItems = items.findByScheduleIdAndDayNo(schedule.getId(), day);
		requireNoOverlap(dayItems, null, start, end);

		List<ScheduleItem> relaid = List.of();
		if (request.index() != null) {
			// 틈에 끼워 넣기: 그 자리에 넣고, 주변 시간 미정 항목의 자리값을 다시 매긴다
			List<ScheduleItem> sequence = Timeline.sorted(dayItems);
			int index = Math.max(0, Math.min(request.index(), sequence.size()));
			if (start != null && !Timeline.fitsGap(sequence, index, start, end)) {
				throw new ApiException(ErrorCode.SCHEDULE_GAP_MISMATCH);
			}
			sequence.add(index, item);
			relaid = new ArrayList<>(Timeline.layout(sequence));
			relaid.remove(item);
		} else if (start == null) {
			// [+] 버튼: 그 Day의 맨 아래 시간 미정으로 넣는다
			item.setAnchorMinute(null);
			item.setSortOrder(nextBottomOrder(dayItems));
		}
		if (start != null) {
			item.setAnchorMinute(null);
			item.setSortOrder(0);
		}
		// [하이라이트] 직접 추가한 항목만 표시한다. 자리값만 다시 매겨진 주변 항목(relaid)은 바꾸지 않는다.
		item.markChanged(ScheduleItem.ChangeType.ADDED, userId);
		try {
			items.saveAndFlush(item);
		} catch (DataIntegrityViolationException e) {
			// uq_schedule_items_place 유니크 위반 -> 거절 메시지로 변환
			throw new ApiException(ErrorCode.SCHEDULE_DUPLICATE);
		}
		items.saveAllAndFlush(relaid);

		String name = place != null ? place.getName() : item.getTitle();
		String when = start == null ? "Day " + day : "Day " + day + " " + Timeline.formatRange(start, end);
		systemMessage(schedule, userId, when + "에 " + name + Timeline.objectParticle(name) + " 추가했습니다.");

		List<ScheduleItem> changed = new ArrayList<>(relaid);
		changed.add(item);
		scheduleEvents.changed(schedule, userId, changed, List.of());
		return mapper.toResponse(item, schedule, place);
	}

	/* ───────── 수정 (시간, 메모, 제목) ───────── */

	/**
	 * 항목 수정. 보낸 필드만 바꾼다.
	 *  - startTime 키가 있으면 시간 변경이다. (값이 null이면 시간 미정으로) endTime은 함께 보낸 값을 쓴다.
	 *  - memo 키가 있으면 메모를, title 키가 있으면 메모 항목의 제목을 바꾼다.
	 */
	@Transactional
	public ScheduleItemResponse updateItem(Long userId, Long tripId, Long itemId, Map<String, Object> patch) {
		guard.requireActive(tripId, userId);
		Schedule schedule = lockWritableByItem(userId, tripId, itemId);
		ScheduleItem item = findItem(itemId);

		boolean timeChanged = false;
		if (patch.containsKey("startTime")) {
			LocalTime start = Times.parse(asText(patch.get("startTime")));
			LocalTime end = Times.parse(asText(patch.get("endTime")));
			requireValidRange(start, end);
			timeChanged = !java.util.Objects.equals(start, item.getStartTime())
					|| !java.util.Objects.equals(end, item.getEndTime());
			if (timeChanged) {
				List<ScheduleItem> dayItems = items.findByScheduleIdAndDayNo(schedule.getId(), item.getDayNo());
				// 시간 미정 항목은 검사에서 제외하고, 시간을 입력하는 시점에 검사한다
				requireNoOverlap(dayItems, item.getId(), start, end);
				if (start == null) {
					// 시간 항목을 시간 미정으로 바꾸면 있던 자리에 남긴다:
					// anchor_minute = 바꾸기 전 start_time(분), sort_order = 그 자리의 미정 항목들보다 앞
					int anchor = Times.minutes(item.getStartTime());
					int first = dayItems.stream()
							.filter(other -> !other.isTimed() && other.getAnchorMinute() != null
									&& other.getAnchorMinute() == anchor)
							.mapToInt(ScheduleItem::getSortOrder).min().orElse(0);
					item.setAnchorMinute(anchor);
					item.setSortOrder(Math.min(0, first) - 1);
				} else {
					// 시간을 넣으면 자리값을 비운다 (시간순으로 자리가 정해진다)
					item.setAnchorMinute(null);
					item.setSortOrder(0);
				}
				item.setStartTime(start);
				item.setEndTime(end);
			}
		}
		if (patch.containsKey("title") && item.isMemo()) {
			item.setTitle(requireTitle(asText(patch.get("title"))));
		}
		if (patch.containsKey("memo")) {
			item.setMemo(normalizeMemo(asText(patch.get("memo"))));
		}
		item.markChanged(timeChanged ? ScheduleItem.ChangeType.TIME_CHANGED : ScheduleItem.ChangeType.EDITED, userId);
		items.saveAndFlush(item);

		Place place = item.getPlaceId() == null ? null : places.findById(item.getPlaceId()).orElse(null);
		if (timeChanged) {
			systemMessage(schedule, userId, "Day " + item.getDayNo() + " " + itemName(item, place) + "의 시간을 "
					+ Timeline.formatRange(item.getStartTime(), item.getEndTime()) + "(으)로 바꿨습니다.");
		}
		scheduleEvents.changed(schedule, userId, List.of(item), List.of());
		return mapper.toResponse(item, schedule, place);
	}

	/* ───────── Day 이동 ───────── */

	/**
	 * [Day 이동] 다른 Day로 옮기면 시간을 비우고(start_time, end_time = NULL),
	 * 옮겨 간 Day의 맨 아래 시간 미정으로 놓는다. 그래서 겹침 검증이 없다.
	 */
	@Transactional
	public ScheduleItemResponse moveToDay(Long userId, Long tripId, Long itemId, Integer dayNo) {
		guard.requireActive(tripId, userId);
		Schedule schedule = lockWritableByItem(userId, tripId, itemId);
		ScheduleItem item = findItem(itemId);
		int day = requireDay(tripId, dayNo);
		Place place = item.getPlaceId() == null ? null : places.findById(item.getPlaceId()).orElse(null);
		if (item.getDayNo() == day) {
			return mapper.toResponse(item, schedule, place);
		}
		int fromDay = item.getDayNo();
		List<ScheduleItem> targetDay = items.findByScheduleIdAndDayNo(schedule.getId(), day);
		item.setDayNo(day);
		item.setStartTime(null);
		item.setEndTime(null);
		item.setAnchorMinute(null);
		item.setSortOrder(nextBottomOrder(targetDay));
		item.markChanged(ScheduleItem.ChangeType.MOVED, userId);
		items.saveAndFlush(item);

		String name = itemName(item, place);
		systemMessage(schedule, userId,
				name + Timeline.objectParticle(name) + " Day " + fromDay + "에서 Day " + day + "(으)로 옮겼습니다.");
		scheduleEvents.changed(schedule, userId, List.of(item), List.of());
		return mapper.toResponse(item, schedule, place);
	}

	/* ───────── 제외 ───────── */

	@Transactional
	public void removeItem(Long userId, Long tripId, Long itemId) {
		guard.requireActive(tripId, userId);
		Schedule schedule = lockWritableByItem(userId, tripId, itemId);
		ScheduleItem item = findItem(itemId);
		Place place = item.getPlaceId() == null ? null : places.findById(item.getPlaceId()).orElse(null);
		String name = itemName(item, place);
		int day = item.getDayNo();
		items.delete(item);
		items.flush();

		systemMessage(schedule, userId, "Day " + day + "에서 " + name + Timeline.objectParticle(name) + " 제외했습니다.");
		scheduleEvents.changed(schedule, userId, List.of(), List.of(itemId));
	}

	/* ───────── 순서 (시간 미정 항목 옮기기) ───────── */

	/**
	 * [타임라인 순서] 화면이 "이 Day의 항목을 이 순서로"라고 항목 ID 목록을 보낸다.
	 * 그 순서를 위에서부터 훑으며 시간 미정 항목의 자리값(anchor_minute, sort_order)을 다시 매긴다.
	 * 받은 순서에서 시간 항목끼리는 start_time 순서여야 한다. 아니면 거절한다.
	 * 사용자가 끌어 옮긴 항목(movedId)만 MOVED로 표시한다.
	 */
	@Transactional
	public List<ScheduleItemResponse> arrangeDay(Long userId, Long tripId, String owner, Integer dayNo,
			List<Long> orderedIds, Long movedId) {
		guard.requireActive(tripId, userId);
		Schedule schedule = lockWritable(userId, tripId, owner);
		int day = requireDay(tripId, dayNo);
		List<ScheduleItem> dayItems = items.findByScheduleIdAndDayNo(schedule.getId(), day);
		Map<Long, ScheduleItem> byId = dayItems.stream()
				.collect(Collectors.toMap(ScheduleItem::getId, Function.identity()));
		// 그 사이 다른 사람이 항목을 넣거나 뺐으면 받은 목록이 지금 상태와 다르다 -> 거절하고 다시 시도하게 한다
		if (orderedIds == null || orderedIds.size() != byId.size()
				|| !new HashSet<>(orderedIds).equals(byId.keySet())) {
			throw new ApiException(ErrorCode.SCHEDULE_ORDER_INVALID);
		}
		List<ScheduleItem> sequence = orderedIds.stream().map(byId::get).toList();
		if (!Timeline.isTimeOrdered(sequence)) {
			throw new ApiException(ErrorCode.SCHEDULE_ORDER_INVALID, "시간이 있는 항목의 순서는 바꿀 수 없습니다.");
		}
		Set<ScheduleItem> changed = new LinkedHashSet<>(Timeline.layout(sequence));
		ScheduleItem moved = movedId == null ? null : byId.get(movedId);
		if (moved != null && !moved.isTimed()) {
			moved.markChanged(ScheduleItem.ChangeType.MOVED, userId);
			changed.add(moved);
		}
		items.saveAllAndFlush(changed);
		if (!changed.isEmpty()) {
			scheduleEvents.changed(schedule, userId, changed, List.of());
		}
		return mapper.toResponses(tripId, Timeline.sorted(dayItems));
	}

	/* ───────── 내부 ───────── */

	/**
	 * 일정 행을 잠근다. owner: "shared"(공동 일정) 또는 회원 ID(그 회원의 개인 일정).
	 * [권한] 다른 멤버의 개인 일정은 읽기 전용이다. (F4-2)
	 */
	private Schedule lockWritable(Long userId, Long tripId, String owner) {
		long ownerKey;
		if ("shared".equalsIgnoreCase(owner)) {
			ownerKey = 0L;
		} else {
			try {
				ownerKey = Long.parseLong(owner);
			} catch (NumberFormatException e) {
				throw new ApiException(ErrorCode.SCHEDULE_NOT_FOUND);
			}
			if (ownerKey != userId) {
				throw new ApiException(ErrorCode.SCHEDULE_READ_ONLY);
			}
		}
		return schedules.findForUpdate(tripId, ownerKey)
				.orElseThrow(() -> new ApiException(ErrorCode.SCHEDULE_NOT_FOUND));
	}

	private Schedule lockWritableByItem(Long userId, Long tripId, Long itemId) {
		Long scheduleId = items.findScheduleIdById(itemId)
				.orElseThrow(() -> new ApiException(ErrorCode.SCHEDULE_ITEM_NOT_FOUND));
		Schedule schedule = schedules.findForUpdateById(scheduleId)
				.filter(found -> found.getTripId().equals(tripId))
				.orElseThrow(() -> new ApiException(ErrorCode.SCHEDULE_ITEM_NOT_FOUND));
		if (!schedule.isShared() && !schedule.getOwnerUserId().equals(userId)) {
			throw new ApiException(ErrorCode.SCHEDULE_READ_ONLY);
		}
		return schedule;
	}

	/** 잠금을 얻은 뒤에 읽는다. 기다리는 사이에 지워졌으면 없는 항목이다. */
	private ScheduleItem findItem(Long itemId) {
		return items.findById(itemId).orElseThrow(() -> new ApiException(ErrorCode.SCHEDULE_ITEM_NOT_FOUND));
	}

	private int requireDay(Long tripId, Integer dayNo) {
		Trip trip = trips.findById(tripId).orElseThrow(() -> new ApiException(ErrorCode.NOT_TRIP_MEMBER));
		if (dayNo == null || dayNo < 1 || dayNo > trip.dayCount()) {
			throw new ApiException(ErrorCode.INVALID_DAY);
		}
		return dayNo;
	}

	/** 종료시간이 있으면 시작시간도 있어야 하고, 종료시간은 시작시간보다 늦어야 한다 */
	private void requireValidRange(LocalTime start, LocalTime end) {
		if (end != null && (start == null || !end.isAfter(start))) {
			throw new ApiException(ErrorCode.INVALID_TIME_RANGE);
		}
	}

	/** 시간 겹침 검증: 같은 일정, 같은 Day 안에서 시간 범위가 겹치면 거절한다 (F4-19) */
	private void requireNoOverlap(List<ScheduleItem> dayItems, Long exceptId, LocalTime start, LocalTime end) {
		ScheduleItem overlap = Timeline.findOverlap(dayItems, exceptId, start, end);
		if (overlap != null) {
			throw conflict(ErrorCode.SCHEDULE_OVERLAP, overlap);
		}
	}

	/** 거절할 때 현재 확정된 항목의 위치를 함께 알려준다 (F4-22) */
	private ApiException conflict(ErrorCode code, ScheduleItem existing) {
		Map<String, Object> where = new LinkedHashMap<>();
		where.put("dayNo", existing.getDayNo());
		where.put("startTime", Times.format(existing.getStartTime()));
		return new ApiException(code, Map.of("conflict", where));
	}

	/** 같은 장소를 한 일정에 넣을 수 있는 횟수. 숙소는 체크인/체크아웃으로 2번, 나머지는 1번. */
	static int maxPlacements(Place place) {
		return place.getCategory() == Place.Category.LODGING ? 2 : 1;
	}

	/** 이미 쓰인 place_slot을 피해 1부터 limit 중 비어 있는 가장 작은 번호 */
	private static int freeSlot(List<ScheduleItem> placed, int limit) {
		Set<Integer> used = placed.stream().map(ScheduleItem::getPlaceSlot).collect(Collectors.toSet());
		for (int slot = 1; slot <= limit; slot++) {
			if (!used.contains(slot)) {
				return slot;
			}
		}
		// placed.size() < limit을 먼저 확인했으므로 여기 오지 않는다
		throw new ApiException(ErrorCode.SCHEDULE_DUPLICATE);
	}

	/** 맨 아래(anchor_minute = NULL) 시간 미정 항목들의 최대 sort_order + 1 */
	private int nextBottomOrder(List<ScheduleItem> dayItems) {
		return dayItems.stream()
				.filter(other -> !other.isTimed() && other.getAnchorMinute() == null)
				.mapToInt(ScheduleItem::getSortOrder).max().orElse(-1) + 1;
	}

	private String requireTitle(String title) {
		String text = title == null ? "" : title.strip();
		if (text.isEmpty() || text.length() > MAX_TITLE_LENGTH) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "제목은 1~30자로 입력해 주세요.");
		}
		return text;
	}

	private String normalizeMemo(String memo) {
		if (memo == null || memo.isBlank()) {
			return null;
		}
		String text = memo.strip();
		if (text.length() > MAX_MEMO_LENGTH) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "메모는 300자까지 입력할 수 있습니다.");
		}
		return text;
	}

	private static String asText(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private String itemName(ScheduleItem item, Place place) {
		if (item.isMemo()) {
			return item.getTitle();
		}
		return place == null ? "장소" : place.getName();
	}

	/** [채팅] 공동 일정이 바뀔 때만 시스템 메시지를 남긴다. 개인 일정의 변경은 남기지 않는다. */
	private void systemMessage(Schedule schedule, Long userId, String text) {
		if (!schedule.isShared()) {
			return;
		}
		String nickname = users.findById(userId).map(user -> user.getNickname()).orElse("알 수 없음");
		chat.postSystem(schedule.getTripId(), userId, nickname + "님이 " + text);
	}
}
