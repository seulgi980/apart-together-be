package com.aparttogether.apart_together_be.place;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class PlaceDtos {

	private PlaceDtos() {
	}

	public record CreatePlaceRequest(
			@NotBlank @Size(max = 200) String placeKey,
			@NotBlank @Size(max = 200) String name,
			@Size(max = 200) String nameEn,
			@Size(max = 300) String address,
			@Size(max = 300) String addressEn,
			@Size(max = 50) String area,
			@DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
			@DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
			@NotBlank @Size(max = 2048) String googleMapsUrl,
			@NotNull Place.Category category,
			@Size(max = 300) String memo) {
	}


	public record UpdatePlaceRequest(
			@NotBlank @Size(max = 200) String name,
			@NotNull Place.Category category,
			@Size(max = 300) String memo) {
	}

	public record VoteRequest(@NotNull PlaceVote.VoteType vote) {
	}

	public record ReferenceRequest(
			@NotBlank @Size(max = 2048) String url,
			@NotBlank @Size(max = 200) String title,
			@Size(max = 300) String memo) {
	}

	public record ReferenceResponse(Long id, String url, String title, String memo, Long addedByUserId) {

		public static ReferenceResponse of(PlaceReference reference) {
			return new ReferenceResponse(reference.getId(), reference.getUrl(), reference.getTitle(),
					reference.getMemo(), reference.getAddedByUserId());
		}
	}

	/**
	 * @param votes 회원 ID -> LIKE/PASS. 취향 상태(좋아요/패스/의견갈림/미평가)는 저장하지 않고 이 투표로 화면이 계산한다.
	 */
	public record PlaceResponse(
			Long id,
			Long tripId,
			String placeKey,
			String name,
			String nameEn,
			String address,
			String addressEn,
			String area,
			BigDecimal latitude,
			BigDecimal longitude,
			String googleMapsUrl,
			String category,
			String memo,
			Long createdByUserId,
			Map<Long, String> votes,
			List<ReferenceResponse> references,
			Instant createdAt) {
	}
}
