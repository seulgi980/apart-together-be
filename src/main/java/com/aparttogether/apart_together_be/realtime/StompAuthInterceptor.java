package com.aparttogether.apart_together_be.realtime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import com.aparttogether.apart_together_be.auth.AuthUser;
import com.aparttogether.apart_together_be.auth.JwtService;
import com.aparttogether.apart_together_be.trip.MembershipGuard;

/**
 * STOMP 인증.
 * 브라우저 WebSocket은 Authorization 헤더를 못 붙이므로 CONNECT 프레임 헤더의 access token으로 검증한다.
 * 구독할 때는 그 방의 ACTIVE 멤버인지 DB에서 확인한다.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

	private static final Pattern TRIP_TOPIC = Pattern.compile("^/topic/trips/(\\d+)$");

	private final JwtService jwtService;
	private final MembershipGuard guard;
	private final WebSocketSessions sessions;

	public StompAuthInterceptor(JwtService jwtService, MembershipGuard guard, WebSocketSessions sessions) {
		this.jwtService = jwtService;
		this.guard = guard;
		this.sessions = sessions;
	}

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
		if (accessor == null || accessor.getCommand() == null) {
			return message;
		}
		StompCommand command = accessor.getCommand();
		if (command == StompCommand.CONNECT) {
			AuthUser user = jwtService.verifyBearer(accessor.getFirstNativeHeader("Authorization"))
					.orElseThrow(() -> new MessageDeliveryException("UNAUTHORIZED"));
			accessor.setUser(user);
			sessions.bind(accessor.getSessionId(), user.id());
		} else if (command == StompCommand.SUBSCRIBE) {
			checkSubscription(requireUser(accessor), accessor.getDestination());
		} else if (command == StompCommand.SEND) {
			requireUser(accessor);
		}
		return message;
	}

	private AuthUser requireUser(StompHeaderAccessor accessor) {
		if (accessor.getUser() instanceof AuthUser user) {
			return user;
		}
		throw new MessageDeliveryException("UNAUTHORIZED");
	}

	private void checkSubscription(AuthUser user, String destination) {
		if (destination == null) {
			throw new MessageDeliveryException("FORBIDDEN");
		}
		if (destination.startsWith("/user/queue/")) {
			return;
		}
		Matcher matcher = TRIP_TOPIC.matcher(destination);
		if (!matcher.matches() || !guard.isActiveMember(Long.valueOf(matcher.group(1)), user.id())) {
			throw new MessageDeliveryException("NOT_TRIP_MEMBER");
		}
	}
}
