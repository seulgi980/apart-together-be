package com.aparttogether.apart_together_be.trip;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class TripDtos {

	private TripDtos() {
	}

	public record CreateTripRequest(
			@NotBlank @Size(max = 100) String title,
			@NotNull LocalDate startDate,
			@NotNull LocalDate endDate) {
	}

	/**
	 * deleteOutOfRangeItems: 기간을 줄여서 사라지는 Day에 일정이 있을 때, 화면에서 확인을 받았으면 true.
	 * true가 아니면 TRIP_PERIOD_HAS_ITEMS(409)로 거절하고 지워질 항목 수를 알려준다. (F1-8)
	 */
	public record UpdateTripRequest(
			@NotBlank @Size(max = 100) String title,
			@NotNull LocalDate startDate,
			@NotNull LocalDate endDate,
			Boolean deleteOutOfRangeItems) {
	}

	public record JoinRequest(@NotBlank String inviteCode) {
	}

	/** status: JOINED(새로 참여 또는 재참여), ALREADY(이미 참여 중) */
	public record JoinResponse(String status, Long tripId) {
	}

	public record PinColorRequest(@NotNull Integer pinColorNo) {
	}

	public record MemberResponse(Long userId, String nickname, String profileImageUrl, Integer pinColorNo,
			Instant joinedAt) {
	}

	/** 방을 떠난 멤버. status: LEFT(스스로 나감), KICKED(내보내짐). 화면에는 "탈퇴한 멤버"로 표시한다. */
	public record FormerMemberResponse(Long userId, String status) {
	}

	public record TripResponse(
			Long id,
			String title,
			LocalDate startDate,
			LocalDate endDate,
			int dayCount,
			Long ownerUserId,
			String inviteCode,
			List<MemberResponse> members,
			List<FormerMemberResponse> formerMembers,
			long unreadCount) {
	}
}
