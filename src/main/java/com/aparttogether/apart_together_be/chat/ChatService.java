package com.aparttogether.apart_together_be.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aparttogether.apart_together_be.common.ApiException;
import com.aparttogether.apart_together_be.common.ErrorCode;
import com.aparttogether.apart_together_be.config.AppProperties;
import com.aparttogether.apart_together_be.place.PlaceRepository;
import com.aparttogether.apart_together_be.realtime.TripEvent;
import com.aparttogether.apart_together_be.realtime.TripEvents;
import com.aparttogether.apart_together_be.trip.MembershipGuard;
import com.aparttogether.apart_together_be.trip.TripMember;
import com.aparttogether.apart_together_be.trip.TripMemberRepository;

@Service
public class ChatService {

	private static final int MAX_CONTENT_LENGTH = 2000;
	private static final int MAX_PAGE_SIZE = 100;

	/**
	 * @param kind            USER(일반 메시지) / SYSTEM(공동 일정 변경, 장소 카드 등록 자동 기록) / PLACE(장소 카드 공유)
	 * @param senderUserId    보낸 회원. SYSTEM은 null
	 * @param sharedPlaceId   kind가 PLACE일 때 공유한 카드. 카드가 삭제됐으면 null ("삭제된 장소 카드")
	 */
	public record ChatMessageResponse(Long id, Long tripId, String kind, Long senderUserId, String content,
			Long sharedPlaceId, Instant createdAt) {

		static ChatMessageResponse of(ChatMessage message) {
			String kind = message.getMessageType() == ChatMessage.MessageType.SYSTEM ? "SYSTEM"
					: message.isPlaceShare() ? "PLACE" : "USER";
			return new ChatMessageResponse(message.getId(), message.getTripId(), kind, message.getSenderUserId(),
					message.getContent(), message.getSharedPlaceId(), message.getCreatedAt());
		}
	}

	/** messages는 오래된 것부터. hasMore가 true면 첫 메시지의 id를 before로 넘겨 더 불러온다. */
	public record ChatPageResponse(List<ChatMessageResponse> messages, boolean hasMore) {
	}

	public record UnreadResponse(long unreadCount, Long lastReadMessageId) {
	}

	private final ChatMessageRepository messages;
	private final TripMemberRepository members;
	private final PlaceRepository places;
	private final MembershipGuard guard;
	private final TripEvents events;
	private final int defaultPageSize;

	public ChatService(ChatMessageRepository messages, TripMemberRepository members, PlaceRepository places,
			MembershipGuard guard, TripEvents events, AppProperties properties) {
		this.messages = messages;
		this.members = members;
		this.places = places;
		this.guard = guard;
		this.events = events;
		this.defaultPageSize = properties.chat().pageSize();
	}

	/**
	 * 메시지 전송. WebSocket(/app/trips/{tripId}/chat)과 REST 양쪽에서 부른다.
	 * [채팅] 장소 카드 공유(shared_place_id)는 같은 여행방의 카드만 허용한다.
	 */
	@Transactional
	public ChatMessageResponse send(Long userId, Long tripId, String content, Long sharedPlaceId) {
		guard.requireActive(tripId, userId);
		ChatMessage message = new ChatMessage();
		message.setTripId(tripId);
		message.setSenderUserId(userId);
		message.setMessageType(ChatMessage.MessageType.USER);
		if (sharedPlaceId != null) {
			if (places.findByIdAndTripId(sharedPlaceId, tripId).isEmpty()) {
				throw new ApiException(ErrorCode.SHARE_NOT_ALLOWED);
			}
			message.setSharedPlaceId(sharedPlaceId);
			// 공유 메시지는 내용을 비워 둔다. 카드가 지워져도 "공유 메시지였다"는 것을 알 수 있다.
			message.setContent("");
		} else {
			String text = content == null ? "" : content.strip();
			if (text.isEmpty() || text.length() > MAX_CONTENT_LENGTH) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED, "메시지는 1~2000자로 입력해 주세요.");
			}
			message.setContent(text);
		}
		return saveAndPublish(message, userId);
	}

	/**
	 * [채팅] 시스템 메시지. 공동 일정이 바뀔 때와 장소 카드를 등록할 때 남긴다. (호출하는 쪽이 판단한다)
	 * 변경과 같은 트랜잭션 안에서 저장되므로, 거절된 요청은 기록되지 않는다.
	 * 알림일 뿐이라 안 읽은 수에는 세지 않는다. (countUnread)
	 */
	@Transactional
	public void postSystem(Long tripId, Long actorUserId, String text) {
		postSystem(tripId, actorUserId, text, null);
	}

	/**
	 * @param placeId 이 알림이 가리키는 장소 카드 (카드 등록 알림). 화면은 이 알림을 받은 뒤에야
	 *                그 카드를 채팅 [+] 목록에 보여준다. 카드가 지워지면 FK가 NULL로 비운다.
	 */
	@Transactional
	public void postSystem(Long tripId, Long actorUserId, String text, Long placeId) {
		ChatMessage message = new ChatMessage();
		message.setTripId(tripId);
		message.setMessageType(ChatMessage.MessageType.SYSTEM);
		message.setSharedPlaceId(placeId);
		message.setContent(text.length() > MAX_CONTENT_LENGTH ? text.substring(0, MAX_CONTENT_LENGTH) : text);
		// 이벤트에는 일정을 바꾼 사람을 실어, 그 사람의 화면에서는 안 읽은 메시지로 세지 않게 한다
		saveAndPublish(message, actorUserId);
	}

	private ChatMessageResponse saveAndPublish(ChatMessage message, Long actorUserId) {
		messages.saveAndFlush(message);
		ChatMessageResponse response = ChatMessageResponse.of(message);
		events.publish(TripEvent.Type.CHAT_MESSAGE, message.getTripId(), actorUserId, response);
		return response;
	}

	/** 이전 메시지 조회 (F6-6). before가 없으면 가장 최근 쪽. */
	@Transactional(readOnly = true)
	public ChatPageResponse page(Long userId, Long tripId, Long before, Integer limit) {
		guard.requireActive(tripId, userId);
		int size = limit == null ? defaultPageSize : Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
		List<ChatMessage> found = new ArrayList<>(messages.findPage(tripId, before, PageRequest.of(0, size + 1)));
		boolean hasMore = found.size() > size;
		if (hasMore) {
			found = found.subList(0, size);
		}
		Collections.reverse(found);
		return new ChatPageResponse(found.stream().map(ChatMessageResponse::of).toList(), hasMore);
	}

	/** 안 읽은 수 (F6-4) */
	@Transactional(readOnly = true)
	public UnreadResponse unread(Long userId, Long tripId) {
		TripMember member = guard.requireActive(tripId, userId);
		long lastRead = member.getLastReadChatId() == null ? 0L : member.getLastReadChatId();
		return new UnreadResponse(messages.countUnread(tripId, lastRead, userId), member.getLastReadChatId());
	}

	/** 읽음 처리. lastReadMessageId가 없으면 방의 마지막 메시지까지 읽은 것으로 한다. 뒤로 돌아가지는 않는다. */
	@Transactional
	public UnreadResponse markRead(Long userId, Long tripId, Long lastReadMessageId) {
		TripMember member = guard.requireActive(tripId, userId);
		Long last = messages.findLastId(tripId);
		if (last != null) {
			long target = lastReadMessageId == null ? last : Math.min(lastReadMessageId, last);
			long current = member.getLastReadChatId() == null ? 0L : member.getLastReadChatId();
			if (target > current) {
				member.setLastReadChatId(target);
				members.saveAndFlush(member);
			}
		}
		long lastRead = member.getLastReadChatId() == null ? 0L : member.getLastReadChatId();
		return new UnreadResponse(messages.countUnread(tripId, lastRead, userId), member.getLastReadChatId());
	}
}
