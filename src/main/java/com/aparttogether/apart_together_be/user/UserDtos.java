package com.aparttogether.apart_together_be.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class UserDtos {

	private UserDtos() {
	}

	public record UserResponse(Long id, String loginId, String nickname, String profileImageUrl) {

		public static UserResponse of(User user) {
			return new UserResponse(user.getId(), user.getLoginId(), user.getNickname(), user.getProfileImageUrl());
		}
	}

	/** profileImageUrl: 사진 업로드 API가 돌려준 주소. null이면 기본 이미지. */
	public record UpdateProfileRequest(
			@NotBlank @Pattern(regexp = UserRules.NICKNAME, message = UserRules.NICKNAME_HELP) String nickname,
			@Size(max = 500) String profileImageUrl) {
	}

	public record ImageResponse(String url) {
	}
}
