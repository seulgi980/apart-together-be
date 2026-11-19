package com.aparttogether.apart_together_be.auth;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authorization: Bearer 헤더의 access token만 인증에 쓴다.
 * 쿠키(refresh token)는 여기서 보지 않는다. 쿠키로는 재발급과 로그아웃만 할 수 있다.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

	private final JwtService jwtService;

	public JwtAuthFilter(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		jwtService.verifyBearer(request.getHeader(HttpHeaders.AUTHORIZATION)).ifPresent(user -> {
			var authentication = new UsernamePasswordAuthenticationToken(user, null, List.of());
			SecurityContextHolder.getContext().setAuthentication(authentication);
		});
		chain.doFilter(request, response);
	}
}
