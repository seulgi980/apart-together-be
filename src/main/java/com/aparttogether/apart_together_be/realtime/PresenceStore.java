package com.aparttogether.apart_together_be.realtime;

import java.util.List;
import java.util.Map;

/**
 * "누가 어느 Day를 보는 중"을 기억하는 부분 (F4-16).
 * 지금은 메모리 구현({@link InMemoryPresenceStore})을 쓰고, 서버를 여러 대로 늘릴 때 Redis 구현으로 바꿔 끼운다.
 */
public interface PresenceStore {

	/** 이 연결(sessionId)의 회원이 이 방의 day를 보고 있다고 기록한다 */
	void put(Long tripId, Long userId, String sessionId, int day);

	/** 방에서 지금 보고 있는 Day: 회원 ID -> Day. 한 회원이 여러 연결이면 가장 최근 것. */
	Map<Long, Integer> snapshot(Long tripId);

	/** 연결이 끊겼을 때. 표시가 바뀐 여행방 ID 목록을 돌려준다. */
	List<Long> removeSession(String sessionId);

	/** 멤버가 방을 나갔을 때 */
	void removeUser(Long tripId, Long userId);
}
