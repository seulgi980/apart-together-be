package com.aparttogether.apart_together_be.realtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/** 서버 1대용 구현 */
@Component
public class InMemoryPresenceStore implements PresenceStore {

	private record Entry(Long userId, int day, long sequence) {
	}

	/** tripId -> (sessionId -> 보고 있는 Day) */
	private final Map<Long, Map<String, Entry>> byTrip = new ConcurrentHashMap<>();
	private final AtomicLong sequence = new AtomicLong();

	@Override
	public void put(Long tripId, Long userId, String sessionId, int day) {
		byTrip.computeIfAbsent(tripId, id -> new ConcurrentHashMap<>())
				.put(sessionId, new Entry(userId, day, sequence.incrementAndGet()));
	}

	@Override
	public Map<Long, Integer> snapshot(Long tripId) {
		Map<Long, Integer> result = new LinkedHashMap<>();
		byTrip.getOrDefault(tripId, Map.of()).values().stream()
				.sorted(Comparator.comparingLong(Entry::sequence))
				.forEach(entry -> result.put(entry.userId(), entry.day()));
		return result;
	}

	@Override
	public List<Long> removeSession(String sessionId) {
		List<Long> changed = new ArrayList<>();
		byTrip.forEach((tripId, sessions) -> {
			if (sessions.remove(sessionId) != null) {
				changed.add(tripId);
			}
		});
		return changed;
	}

	@Override
	public void removeUser(Long tripId, Long userId) {
		Map<String, Entry> sessions = byTrip.get(tripId);
		if (sessions != null) {
			sessions.values().removeIf(entry -> entry.userId().equals(userId));
		}
	}
}
