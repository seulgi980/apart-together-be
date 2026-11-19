package com.aparttogether.apart_together_be.realtime;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.aparttogether.apart_together_be.auth.AuthUser;
import com.aparttogether.apart_together_be.chat.ChatService;
import com.aparttogether.apart_together_be.common.ApiException;
import com.aparttogether.apart_together_be.common.ErrorCode;
import com.aparttogether.apart_together_be.common.ErrorResponse;
import com.aparttogether.apart_together_be.trip.MembershipGuard;

/**
 * WebSocket(STOMP)으로 받는 요청.
 *  - /app/trips/{tripId}/chat      채팅 전송 { content } 또는 { sharedPlaceId }
 *  - /app/trips/{tripId}/presence  "지금 이 Day를 보는 중" { day }
 * 거절되면 보낸 사람의 /user/queue/errors 로 { code, message } 가 간다.
 */
@Controller
public class RealtimeController {

	public record ChatSend(String content, Long sharedPlaceId) {
	}

	public record PresenceUpdate(Integer day) {
	}

	/** userId가 day를 보는 중 */
	public record Viewer(Long userId, int day) {
	}

	private final ChatService chatService;
	private final MembershipGuard guard;
	private final PresenceStore presence;
	private final TripEvents events;

	public RealtimeController(ChatService chatService, MembershipGuard guard, PresenceStore presence,
			TripEvents events) {
		this.chatService = chatService;
		this.guard = guard;
		this.presence = presence;
		this.events = events;
	}

	@MessageMapping("/trips/{tripId}/chat")
	public void chat(@DestinationVariable Long tripId, @Payload ChatSend message, Principal principal) {
		chatService.send(userId(principal), tripId, message.content(), message.sharedPlaceId());
	}

	@MessageMapping("/trips/{tripId}/presence")
	public void presence(@DestinationVariable Long tripId, @Payload PresenceUpdate update, Principal principal,
			@Header(SimpMessageHeaderAccessor.SESSION_ID_HEADER) String sessionId) {
		Long userId = userId(principal);
		guard.requireActive(tripId, userId);
		if (update.day() == null || update.day() < 1) {
			throw new ApiException(ErrorCode.INVALID_DAY);
		}
		presence.put(tripId, userId, sessionId, update.day());
		publishPresence(tripId, userId);
	}

	/** 연결이 끊기면 그 사람의 "보는 중" 표시를 지운다 */
	@EventListener
	public void onDisconnect(SessionDisconnectEvent event) {
		for (Long tripId : presence.removeSession(event.getSessionId())) {
			publishPresence(tripId, null);
		}
	}

	/** 처음 들어왔을 때 지금 누가 어느 Day를 보는지 */
	@GetMapping("/api/trips/{tripId}/presence")
	@ResponseBody
	public Map<String, Object> currentPresence(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId) {
		guard.requireActive(tripId, auth.id());
		return presencePayload(tripId);
	}

	private void publishPresence(Long tripId, Long actorUserId) {
		events.publish(TripEvent.Type.PRESENCE_CHANGED, tripId, actorUserId, presencePayload(tripId));
	}

	private Map<String, Object> presencePayload(Long tripId) {
		List<Viewer> viewers = new ArrayList<>();
		presence.snapshot(tripId).forEach((userId, day) -> viewers.add(new Viewer(userId, day)));
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("viewers", viewers);
		return payload;
	}

	private static Long userId(Principal principal) {
		if (principal instanceof AuthUser user) {
			return user.id();
		}
		throw new ApiException(ErrorCode.UNAUTHORIZED);
	}

	@MessageExceptionHandler(ApiException.class)
	@SendToUser(destinations = "/queue/errors", broadcast = false)
	public Map<String, Object> handleApi(ApiException e) {
		return ErrorResponse.of(e.code(), e.getMessage(), e.details());
	}

	@MessageExceptionHandler(Exception.class)
	@SendToUser(destinations = "/queue/errors", broadcast = false)
	public Map<String, Object> handleOther(Exception e) {
		return ErrorResponse.of(ErrorCode.VALIDATION_FAILED);
	}
}
