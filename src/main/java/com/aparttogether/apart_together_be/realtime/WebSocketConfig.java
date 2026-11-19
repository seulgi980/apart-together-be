package com.aparttogether.apart_together_be.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import com.aparttogether.apart_together_be.config.AppProperties;

/**
 * WebSocket(STOMP).
 *  - 연결 주소: /ws
 *  - 구독: /topic/trips/{tripId} (방마다 하나), /user/queue/errors (내 요청이 거절됐을 때)
 *  - 전송: /app/trips/{tripId}/chat, /app/trips/{tripId}/presence
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	private final AppProperties properties;
	private final StompAuthInterceptor authInterceptor;
	private final WebSocketSessions sessions;

	public WebSocketConfig(AppProperties properties, StompAuthInterceptor authInterceptor,
			WebSocketSessions sessions) {
		this.properties = properties;
		this.authInterceptor = authInterceptor;
		this.sessions = sessions;
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		// 설정값으로 받은 프론트 주소에서 온 연결만 받는다
		registry.addEndpoint("/ws")
				.setAllowedOrigins(properties.cors().allowedOrigins().toArray(String[]::new));
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		ThreadPoolTaskScheduler heartbeatScheduler = new ThreadPoolTaskScheduler();
		heartbeatScheduler.setPoolSize(1);
		heartbeatScheduler.setThreadNamePrefix("ws-heartbeat-");
		heartbeatScheduler.initialize();

		registry.enableSimpleBroker("/topic", "/queue")
				.setHeartbeatValue(new long[] { 10_000, 10_000 })
				.setTaskScheduler(heartbeatScheduler);
		registry.setApplicationDestinationPrefixes("/app");
		registry.setUserDestinationPrefix("/user");
		// 한 사람에게 가는 이벤트의 순서가 뒤바뀌지 않게 한다
		registry.setPreservePublishOrder(true);
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(authInterceptor);
	}

	@Override
	public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
		registration.addDecoratorFactory(sessions);
	}
}
