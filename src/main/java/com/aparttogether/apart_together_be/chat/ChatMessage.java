package com.aparttogether.apart_together_be.chat;

import java.time.Instant;

import com.aparttogether.apart_together_be.common.Times;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 채팅 (F6). SYSTEM 메시지는 sender_user_id가 NULL이다.
 * 장소 카드 공유 메시지는 content를 빈 문자열로 저장한다.
 * (카드가 삭제되면 shared_place_id가 NULL이 되므로, "공유 메시지였다"는 사실을 content로 구분한다.
 *  일반 메시지는 빈 내용으로 보낼 수 없다.)
 */
@Entity
@Table(name = "chat_messages")
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {

	public enum MessageType {
		USER, SYSTEM
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "trip_id", nullable = false)
	private Long tripId;

	@Column(name = "sender_user_id")
	private Long senderUserId;

	@Enumerated(EnumType.STRING)
	@Column(name = "message_type", nullable = false)
	private MessageType messageType;

	/** MEDIUMTEXT (db/chat_long_messages.sql). 사용자 메시지는 ChatService.MAX_CONTENT_LENGTH(20000자)까지 */
	@Column(nullable = false, columnDefinition = "MEDIUMTEXT")
	private String content;

	@Column(name = "shared_place_id")
	private Long sharedPlaceId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public boolean isPlaceShare() {
		return messageType == MessageType.USER && content.isEmpty();
	}

	@PrePersist
	void onCreate() {
		createdAt = Times.now();
	}
}
