package com.aparttogether.apart_together_be.place;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aparttogether.apart_together_be.chat.ChatService;
import com.aparttogether.apart_together_be.common.ApiException;
import com.aparttogether.apart_together_be.common.ErrorCode;
import com.aparttogether.apart_together_be.place.PlaceDtos.CreatePlaceRequest;
import com.aparttogether.apart_together_be.place.PlaceDtos.PlaceResponse;
import com.aparttogether.apart_together_be.place.PlaceDtos.ReferenceRequest;
import com.aparttogether.apart_together_be.place.PlaceDtos.ReferenceResponse;
import com.aparttogether.apart_together_be.realtime.TripEvent;
import com.aparttogether.apart_together_be.realtime.TripEvents;
import com.aparttogether.apart_together_be.schedule.ScheduleEvents;
import com.aparttogether.apart_together_be.schedule.ScheduleItem;
import com.aparttogether.apart_together_be.schedule.ScheduleItemRepository;
import com.aparttogether.apart_together_be.schedule.ScheduleRepository;
import com.aparttogether.apart_together_be.trip.MembershipGuard;
import com.aparttogether.apart_together_be.trip.TripMember;
import com.aparttogether.apart_together_be.trip.TripMemberRepository;
import com.aparttogether.apart_together_be.user.UserRepository;

@Service
public class PlaceService {

	private final PlaceRepository places;
	private final PlaceReferenceRepository references;
	private final PlaceVoteRepository votes;
	private final TripMemberRepository members;
	private final ScheduleRepository schedules;
	private final ScheduleItemRepository scheduleItems;
	private final MembershipGuard guard;
	private final TripEvents events;
	private final ScheduleEvents scheduleEvents;
	private final GooglePlacesClient google;
	private final ChatService chat;
	private final UserRepository users;

	/**
	 * 상세 패널 위쪽 3칸(평점, 영업시간, 웹사이트).
	 * available이 false면 구글 키가 없거나 구글에서 이 장소를 찾지 못한 것이다. 화면은 빈 칸으로 보여준다.
	 */
	public record GoogleInfoResponse(boolean available, GooglePlacesClient.PlaceInfo info) {
	}

	public PlaceService(PlaceRepository places, PlaceReferenceRepository references, PlaceVoteRepository votes,
			TripMemberRepository members, ScheduleRepository schedules, ScheduleItemRepository scheduleItems,
			MembershipGuard guard, TripEvents events, ScheduleEvents scheduleEvents, GooglePlacesClient google,
			ChatService chat, UserRepository users) {
		this.places = places;
		this.references = references;
		this.votes = votes;
		this.members = members;
		this.schedules = schedules;
		this.scheduleItems = scheduleItems;
		this.guard = guard;
		this.events = events;
		this.scheduleEvents = scheduleEvents;
		this.google = google;
		this.chat = chat;
		this.users = users;
	}

	/**
	 * 구글에서 평점/영업시간/웹사이트를 조회한다. DB에 저장하지 않고 열 때마다 물어본다.
	 * (구글 약관상 장소 ID 말고는 오래 저장할 수 없고, 영업시간과 평점은 계속 바뀐다)
	 */
	@Transactional(readOnly = true)
	public GoogleInfoResponse googleInfo(Long userId, Long tripId, Long placeId) {
		guard.requireActive(tripId, userId);
		Place place = findPlace(tripId, placeId);
		if (!google.isEnabled()) {
			return new GoogleInfoResponse(false, GooglePlacesClient.PlaceInfo.empty());
		}
		// place_key가 구글 장소 ID다
		return google.info(place.getPlaceKey())
				.map(info -> new GoogleInfoResponse(true, info))
				.orElseGet(() -> new GoogleInfoResponse(false, GooglePlacesClient.PlaceInfo.empty()));
	}

	/* ───────── 조회 ───────── */

	/** 방의 카드 전체. 모든 카드는 멤버 모두가 함께 본다. */
	@Transactional(readOnly = true)
	public List<PlaceResponse> list(Long userId, Long tripId) {
		guard.requireActive(tripId, userId);
		return toResponses(tripId, places.findByTripIdOrderByNameAscIdAsc(tripId));
	}

	@Transactional(readOnly = true)
	public PlaceResponse get(Long userId, Long tripId, Long placeId) {
		guard.requireActive(tripId, userId);
		return toResponse(findPlace(tripId, placeId));
	}

	/* ───────── 등록 / 수정 / 삭제 ───────── */

	/**
	 * 카드를 등록하고 채팅에 "OO님이 XX 장소 카드를 추가했습니다." 알림을 남긴다.
	 * 이벤트는 커밋 뒤에 등록한 순서대로 나가므로, 방 사람들은 알림(CHAT_MESSAGE)을 받은 다음에 카드(PLACE_UPSERTED)를 받는다.
	 * 알림에는 카드 ID(sharedPlaceId)를 실어, 화면이 "이 카드의 알림이 도착했는지"를 알 수 있게 한다.
	 */
	@Transactional
	public PlaceResponse create(Long userId, Long tripId, CreatePlaceRequest request) {
		guard.requireActive(tripId, userId);
		Place place = insert(userId, tripId, request);
		if (place == null) {
			throw new ApiException(ErrorCode.PLACE_DUPLICATE);
		}
		String nickname = users.findById(userId).map(user -> user.getNickname()).orElse("알 수 없음");
		chat.postSystem(tripId, userId, nickname + "님이 " + place.getName() + " 장소 카드를 추가했습니다.", place.getId());
		PlaceResponse response = toResponse(place);
		publishUpsert(place, userId, response);
		return response;
	}

	/**
	 * 방마다 같은 장소는 1장만 등록할 수 있다 (F2-3). 등록자가 달라도 중복이다.
	 * (trip_id, place_key) 유니크가 동시에 들어온 요청도 막는다. 중복이면 null.
	 */
	private Place insert(Long userId, Long tripId, CreatePlaceRequest request) {
		if (!GoogleMapsUrls.isGoogleMapsUrl(request.googleMapsUrl())) {
			throw new ApiException(ErrorCode.NOT_GOOGLE_MAPS_URL);
		}
		if ((request.latitude() == null) != (request.longitude() == null)) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "위도와 경도는 함께 있어야 합니다.");
		}
		String placeKey = request.placeKey().trim();
		if (places.existsByTripIdAndPlaceKey(tripId, placeKey)) {
			return null;
		}
		Place place = new Place();
		place.setTripId(tripId);
		place.setCreatedByUserId(userId);
		place.setPlaceKey(placeKey);
		place.setName(request.name().trim());
		place.setNameEn(blankToNull(request.nameEn()));
		place.setAddress(blankToNull(request.address()));
		place.setAddressEn(blankToNull(request.addressEn()));
		place.setArea(blankToNull(request.area()));
		place.setAreaEn(blankToNull(request.areaEn()));
		place.setLatitude(request.latitude());
		place.setLongitude(request.longitude());
		place.setGoogleMapsUrl(request.googleMapsUrl().trim());
		place.setCategory(request.category());
		place.setMemo(blankToNull(request.memo()));
		try {
			return places.saveAndFlush(place);
		} catch (DataIntegrityViolationException e) {
			// 동시에 같은 장소를 등록한 경우: uq_places_trip_place 유니크가 막는다
			throw new ApiException(ErrorCode.PLACE_DUPLICATE);
		}
	}

	@Transactional
	public PlaceResponse update(Long userId, Long tripId, Long placeId, String name, Place.Category category,
			String memo) {
		guard.requireActive(tripId, userId);
		Place place = findPlace(tripId, placeId);
		place.setName(name.trim());
		place.setCategory(category);
		place.setMemo(blankToNull(memo));
		places.saveAndFlush(place);
		PlaceResponse response = toResponse(place);
		publishUpsert(place, userId, response);
		// 이 장소가 놓인 일정 항목의 장소명/카테고리도 다시 그리게 한다
		List<ScheduleItem> placed = scheduleItems.findByPlaceId(placeId);
		if (!placed.isEmpty()) {
			scheduleEvents.refreshed(schedules.findByTripId(tripId), userId, placed);
		}
		return response;
	}

	/** 삭제하면 참고자료, 투표, 일정에 배치된 항목도 함께 지워진다 (F2-6, FK CASCADE) */
	@Transactional
	public void delete(Long userId, Long tripId, Long placeId) {
		guard.requireActive(tripId, userId);
		findPlace(tripId, placeId);
		List<ScheduleItem> placed = scheduleItems.findByPlaceId(placeId);
		List<Long> removedItemIds = placed.stream().map(ScheduleItem::getId).toList();
		places.deleteOne(placeId);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("placeId", placeId);
		payload.put("removedItemIds", removedItemIds);
		events.publish(TripEvent.Type.PLACE_DELETED, tripId, userId, payload);
		if (!placed.isEmpty()) {
			scheduleEvents.removed(schedules.findByTripId(tripId), userId, placed);
		}
	}

	/* ───────── 투표 ───────── */

	/** [투표] 모든 카드에 투표할 수 있다. type이 null이면 내 투표를 취소한다. */
	@Transactional
	public PlaceResponse vote(Long userId, Long tripId, Long placeId, PlaceVote.VoteType type) {
		guard.requireActive(tripId, userId);
		findPlace(tripId, placeId);
		if (type == null) {
			votes.deleteByPlaceIdAndUserId(placeId, userId);
		} else {
			PlaceVote vote = votes.findByPlaceIdAndUserId(placeId, userId).orElseGet(() -> {
				PlaceVote created = new PlaceVote();
				created.setPlaceId(placeId);
				created.setUserId(userId);
				return created;
			});
			vote.setVoteType(type);
			try {
				votes.saveAndFlush(vote);
			} catch (DataIntegrityViolationException e) {
				// 같은 사람이 동시에 두 번 누른 경우 (uq_place_votes_place_user)
				throw new ApiException(ErrorCode.CONFLICT);
			}
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("placeId", placeId);
		payload.put("userId", userId);
		payload.put("vote", type == null ? null : type.name());
		events.publish(TripEvent.Type.VOTE_CHANGED, tripId, userId, payload);
		return toResponse(places.findById(placeId).orElseThrow());
	}

	/* ───────── 참고자료 ───────── */

	@Transactional
	public PlaceResponse addReference(Long userId, Long tripId, Long placeId, ReferenceRequest request) {
		guard.requireActive(tripId, userId);
		Place place = findPlace(tripId, placeId);
		PlaceReference reference = new PlaceReference();
		reference.setPlaceId(placeId);
		reference.setAddedByUserId(userId);
		apply(reference, request);
		references.saveAndFlush(reference);
		return publishAndReturn(place, userId);
	}

	@Transactional
	public PlaceResponse updateReference(Long userId, Long tripId, Long placeId, Long referenceId,
			ReferenceRequest request) {
		guard.requireActive(tripId, userId);
		Place place = findPlace(tripId, placeId);
		PlaceReference reference = references.findByIdAndPlaceId(referenceId, placeId)
				.orElseThrow(() -> new ApiException(ErrorCode.REFERENCE_NOT_FOUND));
		apply(reference, request);
		references.saveAndFlush(reference);
		return publishAndReturn(place, userId);
	}

	@Transactional
	public PlaceResponse deleteReference(Long userId, Long tripId, Long placeId, Long referenceId) {
		guard.requireActive(tripId, userId);
		Place place = findPlace(tripId, placeId);
		PlaceReference reference = references.findByIdAndPlaceId(referenceId, placeId)
				.orElseThrow(() -> new ApiException(ErrorCode.REFERENCE_NOT_FOUND));
		references.delete(reference);
		references.flush();
		return publishAndReturn(place, userId);
	}

	private void apply(PlaceReference reference, ReferenceRequest request) {
		String url = request.url().trim();
		if (!(url.startsWith("http://") || url.startsWith("https://"))) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "링크는 http:// 또는 https:// 로 시작해야 합니다.");
		}
		reference.setUrl(url);
		reference.setTitle(request.title().trim());
		reference.setMemo(blankToNull(request.memo()));
	}

	/* ───────── 내부 ───────── */

	/** 이 방의 카드. 다른 방의 카드는 없는 카드처럼 보인다. */
	private Place findPlace(Long tripId, Long placeId) {
		return places.findByIdAndTripId(placeId, tripId)
				.orElseThrow(() -> new ApiException(ErrorCode.PLACE_NOT_FOUND));
	}

	private PlaceResponse publishAndReturn(Place place, Long userId) {
		PlaceResponse response = toResponse(place);
		publishUpsert(place, userId, response);
		return response;
	}

	private void publishUpsert(Place place, Long userId, PlaceResponse response) {
		events.publish(TripEvent.Type.PLACE_UPSERTED, place.getTripId(), userId, response);
	}

	private PlaceResponse toResponse(Place place) {
		return toResponses(place.getTripId(), List.of(place)).get(0);
	}

	private List<PlaceResponse> toResponses(Long tripId, Collection<Place> list) {
		if (list.isEmpty()) {
			return List.of();
		}
		List<Long> ids = list.stream().map(Place::getId).toList();
		// 투표는 현재 멤버의 것만 센다 (나간 멤버의 투표는 나갈 때 지워진다)
		List<Long> activeUserIds = members.findByTripIdOrderByJoinedAtAscIdAsc(tripId).stream()
				.filter(TripMember::isActive).map(TripMember::getUserId).toList();
		Map<Long, Map<Long, String>> votesByPlace = votes.findByPlaceIdIn(ids).stream()
				.filter(vote -> activeUserIds.contains(vote.getUserId()))
				.collect(Collectors.groupingBy(PlaceVote::getPlaceId, Collectors.toMap(PlaceVote::getUserId,
						vote -> vote.getVoteType().name(), (a, b) -> b, LinkedHashMap::new)));
		Map<Long, List<ReferenceResponse>> referencesByPlace = references.findByPlaceIdInOrderByIdAsc(ids).stream()
				.collect(Collectors.groupingBy(PlaceReference::getPlaceId,
						Collectors.mapping(ReferenceResponse::of, Collectors.toList())));
		return list.stream()
				.map(place -> new PlaceResponse(place.getId(), place.getTripId(), place.getPlaceKey(),
						place.getName(), place.getNameEn(), place.getAddress(), place.getAddressEn(), place.getArea(), place.getAreaEn(),
						place.getLatitude(),
						place.getLongitude(), place.getGoogleMapsUrl(), place.getCategory().name(), place.getMemo(),
						place.getCreatedByUserId(),
						votesByPlace.getOrDefault(place.getId(), Map.of()),
						referencesByPlace.getOrDefault(place.getId(), List.of()), place.getCreatedAt()))
				.toList();
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
