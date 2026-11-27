package com.aparttogether.apart_together_be.chat;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aparttogether.apart_together_be.auth.AuthUser;
import com.aparttogether.apart_together_be.chat.ChatService.ChatMessageResponse;
import com.aparttogether.apart_together_be.chat.ChatService.ChatPageResponse;
import com.aparttogether.apart_together_be.chat.ChatService.UnreadResponse;

/** 채팅 REST. 실시간 전송은 WebSocket(/app/trips/{tripId}/chat)으로도 받는다. */
@RestController
@RequestMapping("/api/trips/{tripId}/chat")
public class ChatController {

	/** content: 일반 메시지 내용. sharedPlaceId: 장소 카드 공유일 때 (content는 무시한다) */
	public record SendRequest(String content, Long sharedPlaceId, String clientMessageId) {
	}

	public record ReadRequest(Long lastReadMessageId) {
	}

	private final ChatService chatService;

	public ChatController(ChatService chatService) {
		this.chatService = chatService;
	}

	@GetMapping("/messages")
	public ChatPageResponse messages(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId,
			@RequestParam(required = false) Long before, @RequestParam(required = false) Integer limit) {
		return chatService.page(auth.id(), tripId, before, limit);
	}

	@PostMapping("/messages")
	public ResponseEntity<ChatMessageResponse> send(@AuthenticationPrincipal AuthUser auth,
			@PathVariable Long tripId, @RequestBody SendRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(chatService.send(auth.id(), tripId, request.content(), request.sharedPlaceId(),
						request.clientMessageId()));
	}

	@GetMapping("/unread")
	public UnreadResponse unread(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId) {
		return chatService.unread(auth.id(), tripId);
	}

	@PostMapping("/read")
	public UnreadResponse read(@AuthenticationPrincipal AuthUser auth, @PathVariable Long tripId,
			@RequestBody(required = false) ReadRequest request) {
		return chatService.markRead(auth.id(), tripId, request == null ? null : request.lastReadMessageId());
	}
}
