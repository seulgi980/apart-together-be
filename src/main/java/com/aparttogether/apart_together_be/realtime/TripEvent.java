package com.aparttogether.apart_together_be.realtime;

import java.time.Instant;

/**
 * 방 사람들에게 뿌리는 실시간 이벤트. /topic/trips/{tripId} 로 나간다.
 *
 * @param type        이벤트 종류
 * @param tripId      여행방 ID
 * @param actorUserId 변경한 회원 ID (시스템이 만든 이벤트는 null)
 * @param payload     종류별 내용 (docs/API.md 참고)
 * @param at          서버가 이벤트를 만든 시각
 */
public record TripEvent(Type type, Long tripId, Long actorUserId, Object payload, Instant at) {

	public enum Type {
		/** 채팅 메시지 (사용자, 시스템, 장소 카드 공유) */
		CHAT_MESSAGE,
		/** 일정 항목 추가/수정/이동/순서 변경/제외 */
		SCHEDULE_CHANGED,
		/** 공동 장소 카드 추가 또는 수정 (참고자료 변경, 공동으로 올리기 포함) */
		PLACE_UPSERTED,
		/** 공동 장소 카드 삭제 */
		PLACE_DELETED,
		/** 투표 변경 */
		VOTE_CHANGED,
		/** 멤버 참여 (재참여 포함) */
		MEMBER_JOINED,
		/** 멤버 나감/내보내짐 */
		MEMBER_REMOVED,
		/** 멤버 핀 색 변경, 닉네임/프로필 사진 변경 */
		MEMBER_UPDATED,
		/** 여행방 제목/기간/초대코드 변경 */
		TRIP_UPDATED,
		/** 여행방 삭제 */
		TRIP_DELETED,
		/** "누가 어느 Day를 보는 중" 변경 */
		PRESENCE_CHANGED
	}
}
