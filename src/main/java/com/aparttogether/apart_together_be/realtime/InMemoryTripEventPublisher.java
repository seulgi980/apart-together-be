package com.aparttogether.apart_together_be.realtime;

import org.springframework.stereotype.Component;

/** 서버 1대용 구현: 받은 이벤트를 이 서버에 연결된 사람들에게 바로 전달한다. */
@Component
public class InMemoryTripEventPublisher implements TripEventPublisher {

	private final LocalTripEventDelivery delivery;

	public InMemoryTripEventPublisher(LocalTripEventDelivery delivery) {
		this.delivery = delivery;
	}

	@Override
	public void publish(TripEvent event) {
		delivery.deliver(event);
	}
}
