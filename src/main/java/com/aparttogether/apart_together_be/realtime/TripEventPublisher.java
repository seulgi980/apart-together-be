package com.aparttogether.apart_together_be.realtime;

/**
 * 실시간 이벤트를 방 사람들에게 뿌리는 부분.
 * 지금은 서버 1대라서 메모리 구현({@link InMemoryTripEventPublisher})을 쓴다.
 * 서버를 여러 대로 늘릴 때는 Redis Pub/Sub 구현으로 바꿔 끼운다:
 *   publish()는 Redis 채널에 발행하고, 각 서버의 구독자가 {@link LocalTripEventDelivery#deliver}를 부른다.
 */
public interface TripEventPublisher {

	void publish(TripEvent event);
}
