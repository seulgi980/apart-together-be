package com.aparttogether.apart_together_be.place;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

	/**
	 * 상세 패널 위쪽 3칸(평점, 영업시간, 웹사이트).
	 * available이 false면 구글 키가 없거나 구글에서 이 장소를 찾지 못한 것이다. 화면은 빈 칸으로 보여준다.
	 */
	public record GoogleInfoResponse(boolean available, GooglePlacesClient.PlaceInfo info) {
	}

	public PlaceService(PlaceRepository places, PlaceReferenceRepository references, PlaceVoteRepository votes,
			TripMemberRepository members, ScheduleRepository schedules, ScheduleItemRepository scheduleItems,
			MembershipGuard guard, TripEvents events, ScheduleEvents scheduleEvents, GooglePlacesClient google) {
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
	}

	/**
	 * 구글에서 평점/영업시간/웹사이트를 조회한다. DB에 저장하지 않고 열 때마다 물어본다.
	 * (구글 약관상 장소 ID 말고는 오래 저장할 수 없고, 영업시간과 평점은 계속 바뀐다)
	 */
	@Transactional(readOnly = true)
	public GoogleInfoResponse googleInfo(Long userId, Long tripId, Long placeId) {
		guard.requireActive(tripId, userId);
		Place place = findVisible(userId, tripId, placeId);
		if (!google.isEnabled()) {
			return new GoogleInfoResponse(false, GooglePlacesClient.PlaceInfo.empty());
		}
		// place_key가 구글 장소 ID다
		return google.info(place.getPlaceKey())
				.map(info -> new GoogleInfoResponse(true, info))
				.orElseGet(() -> new GoogleInfoResponse(false, GooglePlacesClient.PlaceInfo.empty()));
	}

	/* ───────── 조회 ───────── */

	/** 내가 볼 수 있는 카드만 돌려준다: 공동 카드 전체 + 내 나만 카드 (F2-9) */
	@Transactional(readOnly = true)
	public List<PlaceResponse> list(Long userId, Long tripId) {
		guard.requireActive(tripId, userId);
		return toResponses(tripId, places.findVisible(tripId, userId));
	}

	@Transactional(readOnly = true)
	public PlaceResponse get(Long userId, Long tripId, Long placeId) {
		guard.requireActive(tripId, userId);
		return toResponse(findVisible(userId, tripId, placeId));
	}

	/* ───────── 등록 / 수정 / 삭제 ───────── */

	@Transactional
	public PlaceResponse create(Long userId, Long tripId, CreatePlaceRequest request) {
		guard.requireActive(tripId, userId);
		Place place = insert(userId, tripId, request);
		if (place == null) {
			throw new ApiException(ErrorCode.PLACE_DUPLICATE);
		}
		PlaceResponse response = toResponse(place);
		publishUpsert(place, userId, response);
		return response;
	}


	/**
	 * 같은 장소는 같은 공개 범위 안에서 중복 등록할 수 없다 (F2-3).
	 * 공동 카드는 dedupe_owner_id = 0, 나만 카드는 등록자 ID로 넣어 (trip_id, place_key, dedupe_owner_id) 유니크가 막는다.
	 * 중복이면 null.
	 */
	private Place insert(Long userId, Long tripId, CreatePlaceRequest request) {
		if (!GoogleMapsUrls.isGoogleMapsUrl(request.googleMapsUrl())) {
			throw new ApiException(ErrorCode.NOT_GOOGLE_MAPS_URL);
		}
		if ((request.latitude() == null) != (request.longitude() == null)) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "위도와 경도는 함께 있어야 합니다.");
		}
		Place.Visibility visibility = request.visibility() == null ? Place.Visibility.SHARED : request.visibility();
		Long dedupeOwnerId = visibility == Place.Visibility.SHARED ? 0L : userId;
		String placeKey = request.placeKey().trim();
		if (places.existsByTripIdAndPlaceKeyAndDedupeOwnerId(tripId, placeKey, dedupeOwnerId)) {
			return null;
		}
		Place place = new Place();
		place.setTripId(tripId);
		place.setCreatedByUserId(userId);
		place.setVisibility(visibility);
		place.setDedupeOwnerId(dedupeOwnerId);
		place.setPlaceKey(placeKey);
		place.setName(request.name().trim());
		place.setNameEn(blankToNull(request.nameEn()));
		place.setAddress(blankToNull(request.address()));
		place.setAddressEn(blankToNull(request.addressEn()));
		place.setArea(blankToNull(request.area()));
		place.setLatitude(request.latitude());
		place.setLongitude(request.longitude());
		place.setGoogleMapsUrl(request.googleMapsUrl().trim());
		place.setCategory(request.category());
		place.setMemo(blankToNull(request.memo()));
		try {
			return places.saveAndFlush(place);
		} catch (DataIntegrityViolationException e) {
			// 동시에 같은 장소를 등록한 경우: uq_places_dedupe 유니크가 막는다
			throw new ApiException(ErrorCode.PLACE_DUPLICATE);
		}
	}

	@Transactional
	public PlaceResponse update(Long userId, Long tripId, Long placeId, String name, Place.Category category,
			String memo) {
		guard.requireActive(tripId, userId);
		Place place = findVisible(userId, tripId, placeId);
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
		Place place = findVisible(userId, tripId, placeId);
		boolean shared = place.isShared();
		List<ScheduleItem> placed = scheduleItems.findByPlaceId(placeId);
		List<Long> removedItemIds = placed.stream().map(ScheduleItem::getId).toList();
		places.deleteOne(placeId);

		if (shared) {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("placeId", placeId);
			payload.put("removedItemIds", removedItemIds);
			events.publish(TripEvent.Type.PLACE_DELETED, tripId, userId, payload);
		}
		if (!placed.isEmpty()) {
			scheduleEvents.removed(schedules.findByTripId(tripId), userId, placed);
		}
	}

	/* ───────── 투표 ───────── */

	/** [투표] 공동(SHARED) 카드에만 허용한다. 나만 카드에는 투표 행을 만들지 않는다. */
	@Transactional
	public PlaceResponse vote(Long userId, Long tripId, Long placeId, PlaceVote.VoteType type) {
		guard.requireActive(tripId, userId);
		Place place = findVisible(userId, tripId, placeId);
		if (!place.isShared()) {
			throw new ApiException(ErrorCode.VOTE_NOT_ALLOWED);
		}
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

	/**
	 * 공동으로 올리기 (F2-10). visibility=SHARED, dedupe_owner_id=0으로 함께 바꾼다.
	 * 이미 같은 장소의 공동 카드가 있으면 PLACE_ALREADY_SHARED. 공동 카드를 나만 카드로 되돌리는 API는 없다.
	 */
	@Transactional
	public PlaceResponse promote(Long userId, Long tripId, Long placeId) {
		guard.requireActive(tripId, userId);
		Place place = findVisible(userId, tripId, placeId);
		if (place.isShared()) {
			return toResponse(place);
		}
		if (places.existsByTripIdAndPlaceKeyAndDedupeOwnerId(tripId, place.getPlaceKey(), 0L)) {
			throw new ApiException(ErrorCode.PLACE_ALREADY_SHARED);
		}
		place.setVisibility(Place.Visibility.SHARED);
		place.setDedupeOwnerId(0L);
		try {
			places.saveAndFlush(place);
		} catch (DataIntegrityViolationException e) {
			throw new ApiException(ErrorCode.PLACE_ALREADY_SHARED);
		}
		PlaceResponse response = toResponse(place);
		publishUpsert(place, userId, response);
		return response;
	}

	/* ───────── 참고자료 ───────── */

	@Transactional
	public PlaceResponse addReference(Long userId, Long tripId, Long placeId, ReferenceRequest request) {
		guard.requireActive(tripId, userId);
		Place place = findVisible(userId, tripId, placeId);
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
		Place place = findVisible(userId, tripId, placeId);
		PlaceReference reference = references.findByIdAndPlaceId(referenceId, placeId)
				.orElseThrow(() -> new ApiException(ErrorCode.REFERENCE_NOT_FOUND));
		apply(reference, request);
		references.saveAndFlush(reference);
		return publishAndReturn(place, userId);
	}

	@Transactional
	public PlaceResponse deleteReference(Long userId, Long tripId, Long placeId, Long referenceId) {
		guard.requireActive(tripId, userId);
		Place place = findVisible(userId, tripId, placeId);
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

	/** [권한] 나만(PRIVATE) 카드는 등록자 본인에게만 조회/수정/삭제를 허용한다. 남에게는 없는 카드처럼 보인다. */
	private Place findVisible(Long userId, Long tripId, Long placeId) {
		return places.findByIdAndTripId(placeId, tripId)
				.filter(place -> place.isVisibleTo(userId))
				.orElseThrow(() -> new ApiException(ErrorCode.PLACE_NOT_FOUND));
	}

	private PlaceResponse publishAndReturn(Place place, Long userId) {
		PlaceResponse response = toResponse(place);
		publishUpsert(place, userId, response);
		return response;
	}

	/** 나만 카드의 변경은 방에 뿌리지 않는다 (다른 멤버가 볼 수 없는 카드) */
	private void publishUpsert(Place place, Long userId, PlaceResponse response) {
		if (place.isShared()) {
			events.publish(TripEvent.Type.PLACE_UPSERTED, place.getTripId(), userId, response);
		}
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
						place.getName(), place.getNameEn(), place.getAddress(), place.getAddressEn(), place.getArea(), place.getLatitude(),
						place.getLongitude(), place.getGoogleMapsUrl(), place.getCategory().name(), place.getMemo(),
						place.getVisibility().name(), place.getCreatedByUserId(),
						votesByPlace.getOrDefault(place.getId(), Map.of()),
						referencesByPlace.getOrDefault(place.getId(), List.of()), place.getCreatedAt()))
				.toList();
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
