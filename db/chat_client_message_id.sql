-- 채팅 메시지 번호표 (보낸 쪽 화면이 만든 ID)
-- 화면은 보낼 때마다 새 ID를 붙이고, 서버는 그 ID를 응답·이벤트·거절 알림에 그대로 돌려준다.
--   - 화면은 이 ID로 "전송 중" 말풍선을 진짜 메시지로 바꾸거나, 실패 표시(재전송/삭제)로 바꾼다.
--   - [재전송]은 같은 ID로 다시 보낸다. 이미 저장된 메시지면 새로 저장하지 않고 저장된 것을 돌려준다(중복 방지).
-- 시스템 메시지와 이 기능 전에 저장된 메시지는 NULL. (UNIQUE는 NULL끼리 겹쳐도 된다)
ALTER TABLE chat_messages
  ADD COLUMN client_message_id VARCHAR(36) NULL COMMENT '보낸 화면이 붙인 메시지 ID (재전송 중복 방지)' AFTER shared_place_id,
  ADD UNIQUE KEY uk_chat_messages_client (sender_user_id, client_message_id);
