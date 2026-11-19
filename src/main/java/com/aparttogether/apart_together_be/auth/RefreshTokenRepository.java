package com.aparttogether.apart_together_be.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	/** 아직 유효한 토큰만 무효로 바꾼다. 바뀐 행 수가 0이면 이미 누가 먼저 썼다는 뜻이다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update RefreshToken t set t.revokedAt = :now where t.id = :id and t.revokedAt is null")
	int revokeIfActive(@Param("id") Long id, @Param("now") Instant now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
	int revokeAllOfUser(@Param("userId") Long userId, @Param("now") Instant now);
}
