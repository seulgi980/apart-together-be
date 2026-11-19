package com.aparttogether.apart_together_be.user;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.aparttogether.apart_together_be.common.ApiException;
import com.aparttogether.apart_together_be.common.ErrorCode;
import com.aparttogether.apart_together_be.config.AppProperties;
import com.aparttogether.apart_together_be.realtime.TripEvent;
import com.aparttogether.apart_together_be.realtime.TripEvents;
import com.aparttogether.apart_together_be.trip.TripMemberRepository;

@Service
public class UserService {

	/** 프로필 사진으로 받는 형식. (SVG는 스크립트를 담을 수 있어서 받지 않는다) */
	private static final Map<String, String> IMAGE_EXTENSIONS = Map.of(
			"image/png", "png",
			"image/jpeg", "jpg",
			"image/webp", "webp",
			"image/gif", "gif");

	private final UserRepository users;
	private final TripMemberRepository members;
	private final TripEvents events;
	private final AppProperties properties;

	public UserService(UserRepository users, TripMemberRepository members, TripEvents events,
			AppProperties properties) {
		this.users = users;
		this.members = members;
		this.events = events;
		this.properties = properties;
	}

	@Transactional(readOnly = true)
	public User get(Long userId) {
		return users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
	}

	/** 닉네임과 프로필 사진 수정 (F1-6). 수정 즉시 참여 중인 모든 방에 알린다. */
	@Transactional
	public User updateProfile(Long userId, String nickname, String profileImageUrl) {
		User user = get(userId);
		if (users.existsByNicknameAndIdNot(nickname, userId)) {
			throw new ApiException(ErrorCode.NICKNAME_TAKEN);
		}
		if (profileImageUrl != null && !profileImageUrl.equals(user.getProfileImageUrl())
				&& !profileImageUrl.startsWith(profileUrlPrefix())) {
			// 업로드 API가 돌려준 주소만 받는다
			throw new ApiException(ErrorCode.INVALID_IMAGE);
		}
		user.setNickname(nickname);
		user.setProfileImageUrl(profileImageUrl);
		try {
			users.saveAndFlush(user);
		} catch (DataIntegrityViolationException e) {
			throw new ApiException(ErrorCode.NICKNAME_TAKEN);
		}
		Map<String, Object> payload = new java.util.LinkedHashMap<>();
		payload.put("userId", user.getId());
		payload.put("nickname", user.getNickname());
		payload.put("profileImageUrl", user.getProfileImageUrl());
		for (Long tripId : members.findActiveTripIdsOf(userId)) {
			events.publish(TripEvent.Type.MEMBER_UPDATED, tripId, userId, payload);
		}
		return user;
	}

	/** 프로필 사진 파일을 저장하고 주소를 돌려준다. 프로필에 반영하려면 updateProfile에 이 주소를 보낸다. */
	public String storeProfileImage(Long userId, MultipartFile file) {
		String extension = file == null || file.isEmpty() ? null
				: IMAGE_EXTENSIONS.get(Objects.toString(file.getContentType(), "").toLowerCase());
		if (extension == null) {
			throw new ApiException(ErrorCode.INVALID_IMAGE, "PNG, JPG, WEBP, GIF 사진만 올릴 수 있습니다.");
		}
		String fileName = userId + "-" + UUID.randomUUID() + "." + extension;
		try {
			Path directory = Path.of(properties.upload().dir(), "profile").toAbsolutePath().normalize();
			Files.createDirectories(directory);
			file.transferTo(directory.resolve(fileName));
		} catch (IOException e) {
			throw new ApiException(ErrorCode.INTERNAL_ERROR, "사진을 저장하지 못했습니다.");
		}
		return profileUrlPrefix() + fileName;
	}

	private String profileUrlPrefix() {
		return properties.publicBaseUrl() + "/uploads/profile/";
	}
}
