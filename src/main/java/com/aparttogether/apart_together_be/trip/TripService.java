package com.aparttogether.apart_together_be.trip;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aparttogether.apart_together_be.chat.ChatMessageRepository;
import com.aparttogether.apart_together_be.common.ApiException;
import com.aparttogether.apart_together_be.common.ErrorCode;
import com.aparttogether.apart_together_be.common.Times;
import com.aparttogether.apart_together_be.config.AppProperties;
import com.aparttogether.apart_together_be.place.PlaceVoteRepository;
import com.aparttogether.apart_together_be.realtime.PresenceStore;
import com.aparttogether.apart_together_be.realtime.TripEvent;
import com.aparttogether.apart_together_be.realtime.TripEvents;
import com.aparttogether.apart_together_be.schedule.Schedule;
import com.aparttogether.apart_together_be.schedule.ScheduleItem;
import com.aparttogether.apart_together_be.schedule.ScheduleItemRepository;
import com.aparttogether.apart_together_be.schedule.ScheduleRepository;
import com.aparttogether.apart_together_be.trip.TripDtos.FormerMemberResponse;
import com.aparttogether.apart_together_be.trip.TripDtos.JoinResponse;
import com.aparttogether.apart_together_be.trip.TripDtos.MemberResponse;
import com.aparttogether.apart_together_be.trip.TripDtos.TripResponse;
import com.aparttogether.apart_together_be.user.User;
import com.aparttogether.apart_together_be.user.UserRepository;

@Service
public class TripService {

	/** 초대코드에 쓰는 글자 (헷갈리는 0/O, 1/I 제외) */
	private static final String INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final int INVITE_LENGTH = 8;

	private final TripRepository trips;
	private final TripMemberRepository members;
	private final UserRepository users;
	private final ScheduleRepository schedules;
	private final ScheduleItemRepository scheduleItems;
	private final PlaceVoteRepository votes;
	private final ChatMessageRepository chats;
	private final MembershipGuard guard;
	private final TripEvents events;
	private final PresenceStore presence;
	private final AppProperties.Trip config;
	private final SecureRandom random = new SecureRandom();

	public TripService(TripRepository trips, TripMemberRepository members, UserRepository users,
			ScheduleRepository schedules, ScheduleItemRepository scheduleItems,
			PlaceVoteRepository votes, ChatMessageRepository chats, MembershipGuard guard, TripEvents events,
			PresenceStore presence, AppProperties properties) {
		this.trips = trips;
		this.members = members;
		this.users = users;
		this.schedules = schedules;
		this.scheduleItems = scheduleItems;
		this.votes = votes;
		this.chats = chats;
		this.guard = guard;
		this.events = events;
		this.presence = presence;
		this.config = properties.trip();
	}

	/* ───────── 조회 ───────── */

	@Transactional(readOnly = true)
	public List<TripResponse> listMine(Long userId) {
		return trips.findActiveTripsOf(userId).stream().map(trip -> toResponse(trip, userId)).toList();
	}

	@Transactional(readOnly = true)
	public TripResponse get(Long userId, Long tripId) {
		guard.requireActive(tripId, userId);
		return toResponse(findTrip(tripId), userId);
	}

	/* ───────── 만들기 / 수정 / 삭제 ───────── */

	/**
	 * [일정 생성] 공동 일정은 여행방을 만드는 트랜잭션 안에서 만든다.
	 * 방장도 멤버이므로 방장의 개인 일정도 함께 만든다.
	 */
	@Transactional
	public TripResponse create(Long userId, String title, LocalDate startDate, LocalDate endDate) {
		validatePeriod(startDate, endDate);
		Trip trip = new Trip();
		trip.setOwnerUserId(userId);
		trip.setTitle(title.trim());
		trip.setStartDate(startDate);
		trip.setEndDate(endDate);
		trip.setInviteCode(newInviteCode());
		trips.saveAndFlush(trip);

		TripMember owner = new TripMember();
		owner.setTripId(trip.getId());
		owner.setUserId(userId);
		owner.setStatus(TripMember.Status.ACTIVE);
		owner.setPinColorNo(1);
		owner.setJoinedAt(Times.now());
		members.save(owner);

		schedules.save(Schedule.shared(trip.getId()));
		schedules.save(Schedule.personal(trip.getId(), userId));
		schedules.flush();
		return toResponse(trip, userId);
	}

	/**
	 * 제목/기간 수정 (방장).
	 * [여행 기간] 최대 일수를 넘을 수 없고, 기간을 줄이면 범위를 벗어난 Day의 일정 항목을 확인 후 삭제한다. (F1-8)
	 */
	@Transactional
	public TripResponse update(Long userId, Long tripId, String title, LocalDate startDate, LocalDate endDate,
			boolean deleteOutOfRangeItems) {
		guard.requireOwner(tripId, userId);
		validatePeriod(startDate, endDate);
		// 이 방의 일정을 모두 잠근 뒤에 지운다. 지우는 사이에 사라질 Day로 항목이 들어오지 못한다.
		for (Schedule schedule : schedules.findByTripId(tripId)) {
			schedules.findForUpdateById(schedule.getId());
		}
		int days = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
		List<ScheduleItem> outOfRange = scheduleItems.findOutOfRange(tripId, days);
		List<Long> removedItemIds = outOfRange.stream().map(ScheduleItem::getId).toList();
		if (!outOfRange.isEmpty()) {
			if (!deleteOutOfRangeItems) {
				long lostDays = outOfRange.stream().map(ScheduleItem::getDayNo).distinct().count();
				throw new ApiException(ErrorCode.TRIP_PERIOD_HAS_ITEMS,
						Map.of("itemCount", outOfRange.size(), "lostDayCount", lostDays));
			}
			scheduleItems.deleteByIds(removedItemIds);
		}
		Trip trip = findTrip(tripId);
		trip.setTitle(title.trim());
		trip.setStartDate(startDate);
		trip.setEndDate(endDate);
		trips.saveAndFlush(trip);

		Map<String, Object> payload = tripPayload(trip);
		payload.put("removedItemIds", removedItemIds);
		events.publish(TripEvent.Type.TRIP_UPDATED, tripId, userId, payload);
		return toResponse(trip, userId);
	}

	/** 초대코드 재발급 (방장). 예전 코드는 더 이상 쓸 수 없다. */
	@Transactional
	public TripResponse regenerateInviteCode(Long userId, Long tripId) {
		Trip trip = guard.requireOwner(tripId, userId);
		trip.setInviteCode(newInviteCode());
		trips.saveAndFlush(trip);
		Map<String, Object> payload = tripPayload(trip);
		payload.put("removedItemIds", List.of());
		events.publish(TripEvent.Type.TRIP_UPDATED, tripId, userId, payload);
		return toResponse(trip, userId);
	}

	/** 방 삭제 (방장). 안의 데이터는 FK CASCADE로 함께 지워진다. */
	@Transactional
	public void delete(Long userId, Long tripId) {
		Trip trip = guard.requireOwner(tripId, userId);
		trips.delete(trip);
		trips.flush();
		events.publish(TripEvent.Type.TRIP_DELETED, tripId, userId, Map.of());
	}

	/* ───────── 참여 / 나가기 / 내보내기 ───────── */

	/**
	 * 초대코드 참여.
	 * [인원 제한] ACTIVE 멤버 수가 최대 인원을 넘지 않게 확인한다.
	 * [핀 색] 방 안에서 겹치지 않게 자동 배정한다.
	 * [재참여] 나갔던(LEFT) 사람은 기존 행을 되살리고, 내보내진(KICKED) 사람은 거절한다.
	 */
	@Transactional
	public JoinResponse join(Long userId, String inviteCode) {
		Trip found = trips.findByInviteCode(inviteCode.trim().toUpperCase())
				.orElseThrow(() -> new ApiException(ErrorCode.INVITE_NOT_FOUND));
		Long tripId = found.getId();
		// 방 행을 잠가 참여 요청을 한 줄로 세운다 (인원 수와 핀 색을 정확히 보기 위해)
		trips.findForUpdate(tripId).orElseThrow(() -> new ApiException(ErrorCode.INVITE_NOT_FOUND));

		TripMember existing = members.findByTripIdAndUserId(tripId, userId).orElse(null);
		if (existing != null && existing.isActive()) {
			return new JoinResponse("ALREADY", tripId);
		}
		if (existing != null && existing.getStatus() == TripMember.Status.KICKED) {
			throw new ApiException(ErrorCode.KICKED_MEMBER);
		}
		List<TripMember> active = activeMembers(tripId);
		if (active.size() >= config.maxMembers()) {
			throw new ApiException(ErrorCode.TRIP_FULL);
		}
		Set<Integer> used = active.stream().map(TripMember::getPinColorNo).collect(Collectors.toSet());
		Integer pinColorNo = null;
		for (int no = 1; no <= config.pinColorCount(); no++) {
			if (!used.contains(no)) {
				pinColorNo = no;
				break;
			}
		}
		if (pinColorNo == null) {
			throw new ApiException(ErrorCode.TRIP_FULL);
		}

		TripMember member = existing != null ? existing : new TripMember();
		member.setTripId(tripId);
		member.setUserId(userId);
		member.setStatus(TripMember.Status.ACTIVE);
		member.setLeftAt(null);
		member.setPinColorNo(pinColorNo);
		member.setJoinedAt(Times.now());
		// 안 읽은 채팅 수는 들어온 시점의 마지막 메시지에 맞춰 0부터 센다
		member.setLastReadChatId(chats.findLastId(tripId));
		members.saveAndFlush(member);

		// [일정 생성] 개인 일정은 참여할 때 만든다. 이미 있으면 다시 만들지 않는다.
		if (schedules.findByTripIdAndOwnerKey(tripId, userId).isEmpty()) {
			schedules.saveAndFlush(Schedule.personal(tripId, userId));
		}

		User user = users.findById(userId).orElseThrow();
		events.publish(TripEvent.Type.MEMBER_JOINED, tripId, userId, toMemberResponse(member, user));
		return new JoinResponse("JOINED", tripId);
	}

	/** 스스로 나가기. 다시 들어올 수 있다. 방장은 나갈 수 없다(방을 삭제해야 한다). */
	@Transactional
	public void leave(Long userId, Long tripId) {
		guard.requireActive(tripId, userId);
		Trip trip = trips.findForUpdate(tripId).orElseThrow(() -> new ApiException(ErrorCode.NOT_TRIP_MEMBER));
		if (trip.getOwnerUserId().equals(userId)) {
			throw new ApiException(ErrorCode.OWNER_CANNOT_LEAVE);
		}
		removeMember(tripId, userId, userId, TripMember.Status.LEFT);
	}

	/** 내보내기 (방장). 내보내진 사람은 다시 들어올 수 없다. */
	@Transactional
	public void kick(Long ownerId, Long tripId, Long targetUserId) {
		guard.requireOwner(tripId, ownerId);
		trips.findForUpdate(tripId);
		if (targetUserId.equals(ownerId)) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "방장은 내보낼 수 없습니다.");
		}
		TripMember target = members.findByTripIdAndUserId(tripId, targetUserId).orElse(null);
		if (target == null || !target.isActive()) {
			throw new ApiException(ErrorCode.MEMBER_NOT_FOUND);
		}
		removeMember(tripId, targetUserId, ownerId, TripMember.Status.KICKED);
	}

	/**
	 * [멤버 나가기/내보내기] 한 트랜잭션 안에서 처리한다. (F1-11)
	 *  1) trip_members: status를 LEFT/KICKED로, pin_color_no를 NULL로, left_at을 현재 시각으로
	 *  2) 그 사용자가 이 방의 카드에 한 투표 삭제
	 *  3) 그 사용자의 개인 일정과 그 안의 항목 삭제
	 * 장소 카드, 참고자료, 채팅은 남긴다.
	 */
	private void removeMember(Long tripId, Long targetUserId, Long actorUserId, TripMember.Status status) {
		votes.deleteByUserInTrip(tripId, targetUserId);
		schedules.deleteByTripIdAndOwnerKey(tripId, targetUserId);

		TripMember member = members.findByTripIdAndUserId(tripId, targetUserId).orElseThrow();
		member.setStatus(status);
		member.setPinColorNo(null);
		member.setLeftAt(Times.now());
		members.saveAndFlush(member);

		presence.removeUser(tripId, targetUserId);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("userId", targetUserId);
		payload.put("status", status.name());
		events.publish(TripEvent.Type.MEMBER_REMOVED, tripId, actorUserId, payload);
	}

	/** 내 핀 색 변경. 방 안에서 겹치지 않는 색만 고를 수 있다. (F1-2) */
	@Transactional
	public MemberResponse changePinColor(Long userId, Long tripId, int pinColorNo) {
		guard.requireActive(tripId, userId);
		trips.findForUpdate(tripId);
		if (pinColorNo < 1 || pinColorNo > config.pinColorCount()) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "고를 수 없는 색입니다.");
		}
		TripMember member = members.findByTripIdAndUserId(tripId, userId).orElseThrow();
		boolean taken = activeMembers(tripId).stream()
				.anyMatch(other -> !other.getUserId().equals(userId) && other.getPinColorNo() == pinColorNo);
		if (taken) {
			throw new ApiException(ErrorCode.PIN_COLOR_TAKEN);
		}
		member.setPinColorNo(pinColorNo);
		try {
			members.saveAndFlush(member);
		} catch (DataIntegrityViolationException e) {
			// uq_trip_members_trip_pin 유니크가 마지막으로 막는다
			throw new ApiException(ErrorCode.PIN_COLOR_TAKEN);
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("userId", userId);
		payload.put("pinColorNo", pinColorNo);
		events.publish(TripEvent.Type.MEMBER_UPDATED, tripId, userId, payload);
		return toMemberResponse(member, users.findById(userId).orElseThrow());
	}

	/* ───────── 내부 ───────── */

	private Trip findTrip(Long tripId) {
		return trips.findById(tripId).orElseThrow(() -> new ApiException(ErrorCode.NOT_TRIP_MEMBER));
	}

	private List<TripMember> activeMembers(Long tripId) {
		return members.findByTripIdOrderByJoinedAtAscIdAsc(tripId).stream().filter(TripMember::isActive).toList();
	}

	/** 종료일은 시작일보다 빠를 수 없고, 기간은 최대 일수 설정값을 넘을 수 없다 */
	private void validatePeriod(LocalDate startDate, LocalDate endDate) {
		if (endDate.isBefore(startDate)) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "종료일은 시작일보다 빠를 수 없습니다.");
		}
		long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
		if (days > config.maxDays()) {
			throw new ApiException(ErrorCode.TRIP_TOO_LONG, Map.of("maxDays", config.maxDays(), "days", days));
		}
	}

	private String newInviteCode() {
		while (true) {
			StringBuilder code = new StringBuilder(INVITE_LENGTH);
			for (int i = 0; i < INVITE_LENGTH; i++) {
				code.append(INVITE_ALPHABET.charAt(random.nextInt(INVITE_ALPHABET.length())));
			}
			if (!trips.existsByInviteCode(code.toString())) {
				return code.toString();
			}
		}
	}

	private Map<String, Object> tripPayload(Trip trip) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("id", trip.getId());
		payload.put("title", trip.getTitle());
		payload.put("startDate", trip.getStartDate().toString());
		payload.put("endDate", trip.getEndDate().toString());
		payload.put("dayCount", trip.dayCount());
		payload.put("inviteCode", trip.getInviteCode());
		return payload;
	}

	private MemberResponse toMemberResponse(TripMember member, User user) {
		return new MemberResponse(member.getUserId(), user.getNickname(), user.getProfileImageUrl(),
				member.getPinColorNo(), member.getJoinedAt());
	}

	private TripResponse toResponse(Trip trip, Long userId) {
		List<TripMember> all = members.findByTripIdOrderByJoinedAtAscIdAsc(trip.getId());
		Map<Long, User> userById = users
				.findAllById(all.stream().filter(TripMember::isActive).map(TripMember::getUserId).toList()).stream()
				.collect(Collectors.toMap(User::getId, Function.identity()));
		List<MemberResponse> active = new ArrayList<>();
		List<FormerMemberResponse> former = new ArrayList<>();
		Long lastReadChatId = null;
		for (TripMember member : all) {
			if (member.isActive()) {
				active.add(toMemberResponse(member, userById.get(member.getUserId())));
				if (member.getUserId().equals(userId)) {
					lastReadChatId = member.getLastReadChatId();
				}
			} else {
				former.add(new FormerMemberResponse(member.getUserId(), member.getStatus().name()));
			}
		}
		long unread = chats.countUnread(trip.getId(), lastReadChatId == null ? 0L : lastReadChatId, userId);
		return new TripResponse(trip.getId(), trip.getTitle(), trip.getStartDate(), trip.getEndDate(),
				trip.dayCount(), trip.getOwnerUserId(), trip.getInviteCode(), active, former, unread);
	}
}
