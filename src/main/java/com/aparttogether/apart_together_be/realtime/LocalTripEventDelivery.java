package com.aparttogether.apart_together_be.realtime;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 이 서버에 WebSocket으로 연결된 사람들에게 이벤트를 전달한다.
 * 어떤 구현(메모리, Redis)으로 이벤트가 오든 마지막에는 여기를 거친다.
 */
@Component
public class LocalTripEventDelivery {

	private static final Logger log = LoggerFactory.getLogger(LocalTripEventDelivery.class);
	private static final long CLOSE_DELAY_MILLIS = 500;

	private final SimpMessagingTemplate messaging;
	private final WebSocketSessions sessions;

	public LocalTripEventDelivery(SimpMessagingTemplate messaging, WebSocketSessions sessions) {
		this.messaging = messaging;
		this.sessions = sessions;
	}

	public static String topic(Long tripId) {
		return "/topic/trips/" + tripId;
	}

	public void deliver(TripEvent event) {
		try {
			messaging.convertAndSend(topic(event.tripId()), event);
		} catch (RuntimeException e) {
			// 이미 저장은 끝났다. 전달 실패가 요청을 실패로 만들면 안 된다.
			log.warn("실시간 이벤트 전달 실패: {} trip={}", event.type(), event.tripId(), e);
		}
		// 나가거나 내보내진 멤버는 더 이상 이 방의 이벤트를 받으면 안 된다.
		// 그 회원의 연결을 끊는다. 프론트가 다시 연결하면 구독 단계에서 멤버 확인을 다시 거친다.
		if (event.type() == TripEvent.Type.MEMBER_REMOVED && event.payload() instanceof Map<?, ?> payload
				&& payload.get("userId") instanceof Long userId) {
			// 방금 보낸 MEMBER_REMOVED 이벤트가 그 사람에게도 도착할 시간을 잠깐 준 뒤에 끊는다.
			CompletableFuture.runAsync(() -> sessions.closeSessionsOf(userId),
					CompletableFuture.delayedExecutor(CLOSE_DELAY_MILLIS, TimeUnit.MILLISECONDS));
		}
	}
}
