package com.aparttogether.apart_together_be.trip;

import org.springframework.stereotype.Component;

import com.aparttogether.apart_together_be.common.ApiException;
import com.aparttogether.apart_together_be.common.ErrorCode;

/**
 * [권한] 방과 관련된 모든 API는 요청자가 그 방의 ACTIVE 멤버인지 DB에서 확인한다. (F1-5)
 * access token이 유효해도 그것만 믿지 않는다. (내보낸 멤버의 토큰이 최대 15분 살아 있기 때문)
 */
@Component
public class MembershipGuard {

	private final TripMemberRepository members;
	private final TripRepository trips;

	public MembershipGuard(TripMemberRepository members, TripRepository trips) {
		this.members = members;
		this.trips = trips;
	}

	public boolean isActiveMember(Long tripId, Long userId) {
		return members.findByTripIdAndUserId(tripId, userId).map(TripMember::isActive).orElse(false);
	}

	/** ACTIVE 멤버가 아니면 NOT_TRIP_MEMBER(403). 방이 없어도 같은 응답이다. */
	public TripMember requireActive(Long tripId, Long userId) {
		return members.findByTripIdAndUserId(tripId, userId)
				.filter(TripMember::isActive)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_TRIP_MEMBER));
	}

	/** ACTIVE 멤버이면서 방장이어야 한다 (F1-10) */
	public Trip requireOwner(Long tripId, Long userId) {
		requireActive(tripId, userId);
		Trip trip = trips.findById(tripId).orElseThrow(() -> new ApiException(ErrorCode.NOT_TRIP_MEMBER));
		if (!trip.getOwnerUserId().equals(userId)) {
			throw new ApiException(ErrorCode.OWNER_ONLY);
		}
		return trip;
	}
}
