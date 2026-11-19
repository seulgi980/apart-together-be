package com.aparttogether.apart_together_be.trip;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aparttogether.apart_together_be.auth.AuthUser;
import com.aparttogether.apart_together_be.trip.TripDtos.CreateTripRequest;
import com.aparttogether.apart_together_be.trip.TripDtos.JoinRequest;
import com.aparttogether.apart_together_be.trip.TripDtos.JoinResponse;
import com.aparttogether.apart_together_be.trip.TripDtos.MemberResponse;
import com.aparttogether.apart_together_be.trip.TripDtos.PinColorRequest;
import com.aparttogether.apart_together_be.trip.TripDtos.TripResponse;
import com.aparttogether.apart_together_be.trip.TripDtos.UpdateTripRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/trips")
public class TripController {

	private final TripService tripService;

	public TripController(TripService tripService) {
		this.tripService = tripService;
	}

	@GetMapping
	public List<TripResponse> list(@AuthenticationPrincipal AuthUser auth) {
		return tripService.listMine(auth.id());
	}

	@PostMapping
	public ResponseEntity<TripResponse> create(@AuthenticationPrincipal AuthUser auth,
			@Valid @RequestBody CreateTripRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(tripService.create(auth.id(), request.title(), request.startDate(), request.endDate()));
	}

	@PostMapping("/join")
	public JoinResponse join(@AuthenticationPrincipal AuthUser auth, @Valid @RequestBody JoinRequest request) {
		return tripService.join(auth.id(), request.inviteCode());
	}

	@GetMapping("/{tripId}")
	public TripResponse get(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId) {
		return tripService.get(auth.id(), tripId);
	}

	@PatchMapping("/{tripId}")
	public TripResponse update(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId,
			@Valid @RequestBody UpdateTripRequest request) {
		return tripService.update(auth.id(), tripId, request.title(), request.startDate(), request.endDate(),
				Boolean.TRUE.equals(request.deleteOutOfRangeItems()));
	}

	@DeleteMapping("/{tripId}")
	public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId) {
		tripService.delete(auth.id(), tripId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{tripId}/invite-code")
	public TripResponse regenerateInviteCode(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId) {
		return tripService.regenerateInviteCode(auth.id(), tripId);
	}

	@PostMapping("/{tripId}/leave")
	public ResponseEntity<Void> leave(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId) {
		tripService.leave(auth.id(), tripId);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{tripId}/members/{userId}")
	public ResponseEntity<Void> kick(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId,
			@PathVariable Long userId) {
		tripService.kick(auth.id(), tripId, userId);
		return ResponseEntity.noContent().build();
	}

	@PatchMapping("/{tripId}/members/me")
	public MemberResponse changePinColor(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId,
			@Valid @RequestBody PinColorRequest request) {
		return tripService.changePinColor(auth.id(), tripId, request.pinColorNo());
	}
}
