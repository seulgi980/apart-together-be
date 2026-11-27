-- 채팅 메시지 길이 2000자 -> 20000자
-- VARCHAR(20000)은 utf8mb4에서 최대 80000바이트라 MySQL 한 줄 제한(65535바이트)을 넘는다.
-- 그래서 MEDIUMTEXT(최대 16MB)로 바꾸고, 글자 수 제한(20000자)은 서버 코드(ChatService)가 지킨다.
-- 이미 있는 메시지는 그대로 남는다. (늘리기만 하므로 잘리는 데이터가 없다)
ALTER TABLE chat_messages
  MODIFY COLUMN content MEDIUMTEXT NOT NULL COMMENT '메시지내용 (최대 20000자, 서버에서 검사)';
