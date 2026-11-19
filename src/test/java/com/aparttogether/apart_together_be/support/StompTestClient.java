package com.aparttogether.apart_together_be.support;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import tools.jackson.databind.JsonNode;

/** 테스트용 STOMP 클라이언트. 브라우저처럼 CONNECT 프레임 헤더에 access token을 싣는다. */
public class StompTestClient implements AutoCloseable {

	private final WebSocketStompClient client;
	private final StompSession session;
	/** 서버가 보낸 ERROR 프레임, 연결 오류 */
	public final BlockingQueue<String> errors;

	private StompTestClient(WebSocketStompClient client, StompSession session, BlockingQueue<String> errors) {
		this.client = client;
		this.session = session;
		this.errors = errors;
	}

	/** token이 null이면 Authorization 헤더 없이 연결을 시도한다. origin이 null이면 Origin 헤더를 보내지 않는다. */
	public static StompTestClient connect(int port, String token, String origin) throws Exception {
		WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
		// JSON 본문을 문자열 그대로 주고받는다 (content-type이 application/json이어도 문자열로 다룬다)
		client.setMessageConverter(new StringMessageConverter(StandardCharsets.UTF_8) {
			@Override
			protected boolean supportsMimeType(org.springframework.messaging.MessageHeaders headers) {
				return true;
			}
		});
		WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
		if (origin != null) {
			handshake.setOrigin(origin);
		}
		StompHeaders connect = new StompHeaders();
		if (token != null) {
			connect.add("Authorization", "Bearer " + token);
		}
		BlockingQueue<String> errors = new LinkedBlockingQueue<>();
		StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", handshake, connect,
				new StompSessionHandlerAdapter() {
					@Override
					public void handleFrame(StompHeaders headers, Object payload) {
						errors.add("ERROR " + headers.getFirst("message"));
					}

					@Override
					public void handleException(StompSession s, StompCommand command, StompHeaders headers,
							byte[] payload, Throwable exception) {
						errors.add("EXCEPTION " + exception);
					}

					@Override
					public void handleTransportError(StompSession s, Throwable exception) {
						errors.add("TRANSPORT " + exception);
					}
				}).get(5, TimeUnit.SECONDS);
		return new StompTestClient(client, session, errors);
	}

	public static StompTestClient connect(int port, String token) throws Exception {
		return connect(port, token, null);
	}

	/** 받은 이벤트를 차례로 쌓는 구독 */
	public class Subscription {

		private final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();

		/** 이 종류의 이벤트가 올 때까지 기다린다 (다른 종류는 건너뛴다). 시간 안에 안 오면 null. */
		public JsonNode await(String type) throws InterruptedException {
			long deadline = System.currentTimeMillis() + 5000;
			while (System.currentTimeMillis() < deadline) {
				JsonNode event = events.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
				if (event == null) {
					return null;
				}
				if (type.equals(event.path("type").asString(""))) {
					return event;
				}
			}
			return null;
		}

		/** 지금까지 받은 것 중 이 종류가 있는지 (millis 동안 더 기다려 본다) */
		public List<JsonNode> drain(long millis) throws InterruptedException {
			Thread.sleep(millis);
			List<JsonNode> all = new ArrayList<>();
			events.drainTo(all);
			return all;
		}
	}

	public Subscription subscribe(String destination) throws InterruptedException {
		Subscription subscription = new Subscription();
		session.subscribe(destination, new StompFrameHandler() {
			@Override
			public Type getPayloadType(StompHeaders headers) {
				return String.class;
			}

			@Override
			public void handleFrame(StompHeaders headers, Object payload) {
				subscription.events.add(TestApi.MAPPER.readTree((String) payload));
			}
		});
		// 구독이 서버에 등록될 시간을 준다
		Thread.sleep(300);
		return subscription;
	}

	public Subscription subscribeTrip(long tripId) throws InterruptedException {
		return subscribe("/topic/trips/" + tripId);
	}

	public void send(String destination, Object body) {
		StompHeaders headers = new StompHeaders();
		headers.setDestination(destination);
		headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
		session.send(headers, TestApi.MAPPER.writeValueAsString(body));
	}

	public boolean isConnected() {
		return session.isConnected();
	}

	@Override
	public void close() {
		try {
			if (session.isConnected()) {
				session.disconnect();
			}
		} catch (RuntimeException ignored) {
			// 이미 끊긴 연결
		}
		client.stop();
	}
}
