-- ============================================================
-- refresh token 저장 테이블 (schema.sql v3에 없어서 추가)
-- schema.sql 다음에 실행한다.
--  * 원문이 아니라 SHA-256 해시(16진수 64자)만 저장한다.
--  * 재발급(회전)하면 이전 행의 revoked_at을 채운다.
--  * 이미 무효가 된 토큰이 다시 들어오면 그 회원의 토큰을 전부 무효로 한다.
-- ============================================================
CREATE TABLE refresh_tokens (
  id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT 'refresh token ID',
  user_id     BIGINT   NOT NULL COMMENT '회원 ID',
  token_hash  CHAR(64) NOT NULL COMMENT '토큰 원문의 SHA-256 해시 (16진수)',
  expires_at  DATETIME NOT NULL COMMENT '만료일시 (UTC)',
  revoked_at  DATETIME NULL     COMMENT '무효 처리 일시 (회전, 로그아웃, 재사용 감지). NULL이면 유효',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '발급일시',
  PRIMARY KEY (id),
  UNIQUE KEY uq_refresh_tokens_hash (token_hash),
  KEY idx_refresh_tokens_user (user_id),
  CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='refresh token';
