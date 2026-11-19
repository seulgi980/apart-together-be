package com.aparttogether.apart_together_be.realtime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.aparttogether.apart_together_be.common.Times;

/**
 * 서비스가 이벤트를 내보낼 때 쓰는 창구.
 * 변경은 항상 MySQL에 먼저 저장하고, 트랜잭션이 커밋된 뒤에 이벤트를 뿌린다.
 * (롤백되면 이벤트도 나가지 않는다)
 */
@Component
public class TripEvents {

	private final TripEventPublisher publisher;

	public TripEvents(TripEventPublisher publisher) {
		this.publisher = publisher;
	}

	public void publish(TripEvent.Type type, Long tripId, Long actorUserId, Object payload) {
		TripEvent event = new TripEvent(type, tripId, actorUserId, payload, Times.now());
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					publisher.publish(event);
				}
			});
		} else {
			publisher.publish(event);
		}
	}
}
