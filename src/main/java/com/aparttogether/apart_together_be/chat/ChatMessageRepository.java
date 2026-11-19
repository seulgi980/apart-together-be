package com.aparttogether.apart_together_be.chat;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

	/** 방의 마지막 메시지 ID. 메시지가 없으면 null. */
	@Query("select max(m.id) from ChatMessage m where m.tripId = :tripId")
	Long findLastId(@Param("tripId") Long tripId);

	/** 최신순으로 한 쪽. before가 있으면 그보다 예전 메시지. */
	@Query("""
			select m from ChatMessage m
			where m.tripId = :tripId and (:before is null or m.id < :before)
			order by m.id desc
			""")
	List<ChatMessage> findPage(@Param("tripId") Long tripId, @Param("before") Long before, Pageable pageable);

	/** 안 읽은 수: 마지막으로 읽은 메시지 뒤에 온, 남이 보낸 메시지(시스템 메시지 포함) */
	@Query("""
			select count(m) from ChatMessage m
			where m.tripId = :tripId and m.id > :lastReadId
			  and (m.senderUserId is null or m.senderUserId <> :userId)
			""")
	long countUnread(@Param("tripId") Long tripId, @Param("lastReadId") long lastReadId,
			@Param("userId") Long userId);
}
