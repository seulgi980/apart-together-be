package com.aparttogether.apart_together_be.user;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.aparttogether.apart_together_be.auth.AuthUser;
import com.aparttogether.apart_together_be.user.UserDtos.ImageResponse;
import com.aparttogether.apart_together_be.user.UserDtos.UpdateProfileRequest;
import com.aparttogether.apart_together_be.user.UserDtos.UserResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/users/me")
public class UserController {

	private final UserService userService;

	public UserController(UserService userService) {
		this.userService = userService;
	}

	@GetMapping
	public UserResponse me(@AuthenticationPrincipal AuthUser auth) {
		return UserResponse.of(userService.get(auth.id()));
	}

	@PatchMapping
	public UserResponse update(@AuthenticationPrincipal AuthUser auth,
			@Valid @RequestBody UpdateProfileRequest request) {
		return UserResponse.of(userService.updateProfile(auth.id(), request.nickname(), request.profileImageUrl()));
	}

	@PostMapping("/profile-image")
	public ImageResponse uploadImage(@AuthenticationPrincipal AuthUser auth,
			@RequestParam("file") MultipartFile file) {
		return new ImageResponse(userService.storeProfileImage(auth.id(), file));
	}
}
