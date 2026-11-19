package com.aparttogether.apart_together_be.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.aparttogether.apart_together_be.auth.JwtAuthFilter;
import com.aparttogether.apart_together_be.auth.JwtService;
import com.aparttogether.apart_together_be.common.ErrorCode;

@Configuration
public class SecurityConfig implements WebMvcConfigurer {

	private final AppProperties properties;

	public SecurityConfig(AppProperties properties) {
		this.properties = properties;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService) throws Exception {
		http
				// 기능 API는 쿠키가 아니라 Authorization 헤더로만 인증하므로 CSRF 토큰이 필요 없다.
				// 쿠키를 쓰는 재발급/로그아웃은 POST만 받고, SameSite=Lax + 허용한 출처만 CORS 통과로 막는다.
				.csrf(csrf -> csrf.disable())
				.cors(Customizer.withDefaults())
				// 로그인 상태를 서버 메모리(세션)에 두지 않는다
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.formLogin(form -> form.disable())
				.httpBasic(basic -> basic.disable())
				.logout(logout -> logout.disable())
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
						.requestMatchers("/api/auth/**", "/api/config", "/error").permitAll()
						.requestMatchers(HttpMethod.GET, "/uploads/**").permitAll()
						// WebSocket 연결(handshake)은 열어 두고, STOMP CONNECT 프레임의 access token으로 인증한다
						.requestMatchers("/ws/**").permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint((request, response, e) -> writeError(response, ErrorCode.UNAUTHORIZED))
						.accessDeniedHandler((request, response, e) -> writeError(response, ErrorCode.UNAUTHORIZED)))
				.addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	private static void writeError(jakarta.servlet.http.HttpServletResponse response, ErrorCode code)
			throws java.io.IOException {
		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter()
				.write("{\"code\":\"" + code.name() + "\",\"message\":\"" + code.defaultMessage() + "\"}");
	}

	/** 설정값으로 받은 프론트 주소만 허용하고 자격 증명(쿠키)을 허용한다. 전체 허용(*)은 쓰지 않는다. */
	@Bean
	CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(properties.cors().allowedOrigins());
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		config.setAllowCredentials(true);
		config.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	/** 업로드한 프로필 사진을 /uploads/** 로 내려준다 */
	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		String location = Path.of(properties.upload().dir()).toAbsolutePath().normalize().toUri().toString();
		registry.addResourceHandler("/uploads/**")
				.addResourceLocations(location.endsWith("/") ? location : location + "/");
	}
}
