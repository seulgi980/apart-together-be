package com.aparttogether.apart_together_be.schedule;

import java.util.List;
import java.util.Map;

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
import com.aparttogether.apart_together_be.schedule.ScheduleItemMapper.ScheduleItemResponse;
import com.aparttogether.apart_together_be.schedule.ScheduleService.AddItemRequest;

/**
 * 일정 API. {owner}는 "shared"(공동 일정) 또는 회원 ID(그 회원의 개인 일정)다.
 */
@RestController
@RequestMapping("/api/trips/{tripId}")
public class ScheduleController {

	public record MoveDayRequest(Integer dayNo) {
	}

	public record ArrangeRequest(List<Long> orderedIds, Long movedId) {
	}

	private final ScheduleService scheduleService;

	public ScheduleController(ScheduleService scheduleService) {
		this.scheduleService = scheduleService;
	}

	@GetMapping("/schedule-items")
	public List<ScheduleItemResponse> list(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId) {
		return scheduleService.list(auth.id(), tripId);
	}

	@PostMapping("/schedules/{owner}/items")
	public ResponseEntity<ScheduleItemResponse> add(@AuthenticationPrincipal AuthUser auth,
			@PathVariable Long tripId, @PathVariable String owner, @RequestBody AddItemRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(scheduleService.addItem(auth.id(), tripId, owner, request));
	}

	@PutMapping("/schedules/{owner}/days/{dayNo}/order")
	public List<ScheduleItemResponse> arrange(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId,
			@PathVariable String owner, @PathVariable Integer dayNo, @RequestBody ArrangeRequest request) {
		return scheduleService.arrangeDay(auth.id(), tripId, owner, dayNo, request.orderedIds(), request.movedId());
	}

	@PatchMapping("/schedule-items/{itemId}")
	public ScheduleItemResponse update(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId,
			@PathVariable Long itemId, @RequestBody Map<String, Object> patch) {
		return scheduleService.updateItem(auth.id(), tripId, itemId, patch);
	}

	@PostMapping("/schedule-items/{itemId}/move")
	public ScheduleItemResponse move(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId,
			@PathVariable Long itemId, @RequestBody MoveDayRequest request) {
		return scheduleService.moveToDay(auth.id(), tripId, itemId, request.dayNo());
	}

	@DeleteMapping("/schedule-items/{itemId}")
	public ResponseEntity<Void> remove(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId,
			@PathVariable Long itemId) {
		scheduleService.removeItem(auth.id(), tripId, itemId);
		return ResponseEntity.noContent().build();
	}
}
