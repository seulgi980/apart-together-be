package com.aparttogether.apart_together_be.place;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aparttogether.apart_together_be.auth.AuthUser;
import com.aparttogether.apart_together_be.place.PlaceDtos.CreatePlaceRequest;
import com.aparttogether.apart_together_be.place.PlaceDtos.PlaceResponse;
import com.aparttogether.apart_together_be.place.PlaceDtos.ReferenceRequest;
import com.aparttogether.apart_together_be.place.PlaceDtos.UpdatePlaceRequest;
import com.aparttogether.apart_together_be.place.PlaceDtos.VoteRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/trips/{tripId}/places")
public class PlaceController {

	private final PlaceService placeService;

	public PlaceController(PlaceService placeService) {
		this.placeService = placeService;
	}

	@GetMapping
	public List<PlaceResponse> list(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId) {
		return placeService.list(auth.id(), tripId);
	}

	@PostMapping
	public ResponseEntity<PlaceResponse> create(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId,
			@Valid @RequestBody CreatePlaceRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(placeService.create(auth.id(), tripId, request));
	}

	@GetMapping("/{placeId}")
	public PlaceResponse get(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId,
			@PathVariable Long placeId) {
		return placeService.get(auth.id(), tripId, placeId);
	}

	/** 구글의 평점/영업시간/웹사이트 (상세 패널을 열 때 부른다) */
	@GetMapping("/{placeId}/google-info")
	public PlaceService.GoogleInfoResponse googleInfo(@AuthenticationPrincipal AuthUser auth,
			@PathVariable Long tripId, @PathVariable Long placeId) {
		return placeService.googleInfo(auth.id(), tripId, placeId);
	}

	@PatchMapping("/{placeId}")
	public PlaceResponse update(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId,
			@PathVariable Long placeId, @Valid @RequestBody UpdatePlaceRequest request) {
		return placeService.update(auth.id(), tripId, placeId, request.name(), request.category(), request.memo());
	}

	@DeleteMapping("/{placeId}")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId,
			@PathVariable Long placeId) {
		placeService.delete(auth.id(), tripId, placeId);
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/{placeId}/vote")
	public PlaceResponse vote(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId,
			@PathVariable Long placeId, @Valid @RequestBody VoteRequest request) {
		return placeService.vote(auth.id(), tripId, placeId, request.vote());
	}

	@DeleteMapping("/{placeId}/vote")
	public PlaceResponse cancelVote(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId,
			@PathVariable Long placeId) {
		return placeService.vote(auth.id(), tripId, placeId, null);
	}

	@PostMapping("/{placeId}/references")
	public ResponseEntity<PlaceResponse> addReference(@AuthenticationPrincipal AuthUser auth,
			@PathVariable Long tripId, @PathVariable Long placeId, @Valid @RequestBody ReferenceRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(placeService.addReference(auth.id(), tripId, placeId, request));
	}

	@PatchMapping("/{placeId}/references/{referenceId}")
	public PlaceResponse updateReference(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId,
			@PathVariable Long placeId, @PathVariable Long referenceId, @Valid @RequestBody ReferenceRequest request) {
		return placeService.updateReference(auth.id(), tripId, placeId, referenceId, request);
	}

	@DeleteMapping("/{placeId}/references/{referenceId}")
	public PlaceResponse deleteReference(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId,
			@PathVariable Long placeId, @PathVariable Long referenceId) {
		return placeService.deleteReference(auth.id(), tripId, placeId, referenceId);
	}
}
