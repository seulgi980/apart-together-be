package com.aparttogether.apart_together_be.auth;

import java.security.Principal;

/** access token으로 확인한 요청자. 서버 메모리에 로그인 상태를 두지 않으므로 토큰에서 매번 만든다. */
public record AuthUser(Long id) implements Principal {

	@Override
	public String getName() {
		return String.valueOf(id);
	}
}
