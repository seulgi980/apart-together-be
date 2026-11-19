package com.aparttogether.apart_together_be.realtime;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

/**
 * 이 서버에 열려 있는 WebSocket 연결 목록.
 * (연결 자체는 원래 서버 프로세스에 묶여 있는 것이라, 서버를 여러 대로 늘려도 서버마다 따로 가진다)
 * 내보내진 멤버의 연결을 끊는 데 쓴다.
 */
@Component
public class WebSocketSessions implements WebSocketHandlerDecoratorFactory {

	private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
	private final Map<String, Long> userBySession = new ConcurrentHashMap<>();

	@Override
	public WebSocketHandler decorate(WebSocketHandler handler) {
		return new WebSocketHandlerDecorator(handler) {
			@Override
			public void afterConnectionEstablished(WebSocketSession session) throws Exception {
				sessions.put(session.getId(), session);
				super.afterConnectionEstablished(session);
			}

			@Override
			public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
				sessions.remove(session.getId());
				userBySession.remove(session.getId());
				super.afterConnectionClosed(session, status);
			}
		};
	}

	/** STOMP CONNECT에서 인증이 끝나면 연결과 회원을 묶어 둔다 */
	public void bind(String sessionId, Long userId) {
		userBySession.put(sessionId, userId);
	}

	public void closeSessionsOf(Long userId) {
		userBySession.forEach((sessionId, owner) -> {
			if (!owner.equals(userId)) {
				return;
			}
			WebSocketSession session = sessions.get(sessionId);
			if (session != null && session.isOpen()) {
				try {
					session.close(CloseStatus.POLICY_VIOLATION);
				} catch (IOException ignored) {
					// 이미 끊긴 연결
				}
			}
		});
	}
}
