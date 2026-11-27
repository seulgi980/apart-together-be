package com.aparttogether.apart_together_be.realtime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import com.aparttogether.apart_together_be.config.AppProperties;

/**
 * WebSocket(STOMP).
 *  - 연결 주소: /ws
 *  - 구독: /topic/trips/{tripId} (방마다 하나), /user/queue/errors (내 요청이 거절됐을 때)
 *  - 전송: /app/trips/{tripId}/chat, /app/trips/{tripId}/presence
 *  - 한 프레임 최대 크기: MESSAGE_SIZE_LIMIT (채팅 20000자를 담을 수 있게)
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	/**
	 * 받는 메시지 한 개의 최대 바이트.
	 * 채팅 20000자가 전부 이모지(4바이트)여도 80KB에 JSON·STOMP 헤더가 붙는다. 넉넉히 256KB.
	 * 기본값(Tomcat 8KB, STOMP 64KB)이면 한글 약 2700자부터 연결이 끊긴다.
	 */
	private static final int MESSAGE_SIZE_LIMIT = 256 * 1024;

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
		registration.setMessageSizeLimit(MESSAGE_SIZE_LIMIT);
	}

	/** Tomcat이 WebSocket 텍스트 메시지를 받는 버퍼. STOMP 제한보다 작으면 여기서 먼저 끊긴다. */
	@Bean
	public ServletServerContainerFactoryBean webSocketContainer() {
		ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
		container.setMaxTextMessageBufferSize(MESSAGE_SIZE_LIMIT);
		container.setMaxBinaryMessageBufferSize(MESSAGE_SIZE_LIMIT);
		return container;
	}
}
