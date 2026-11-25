package com.aparttogether.apart_together_be.common;

import org.springframework.http.HttpStatus;

/** API 오류 코드. 응답 본문의 code 값으로 내려간다. */
public enum ErrorCode {
	// 공통
	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없습니다."),
	CONFLICT(HttpStatus.CONFLICT, "이미 처리되었거나 다른 변경과 충돌했습니다."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

	// 로그인
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),
	INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."),
	LOGIN_ID_TAKEN(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다."),
	NICKNAME_TAKEN(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
	INVALID_IMAGE(HttpStatus.BAD_REQUEST, "사용할 수 없는 이미지입니다."),

	// 여행방
	NOT_TRIP_MEMBER(HttpStatus.FORBIDDEN, "이 여행방의 참여자가 아닙니다."),
	OWNER_ONLY(HttpStatus.FORBIDDEN, "방장만 할 수 있습니다."),
	OWNER_CANNOT_LEAVE(HttpStatus.CONFLICT, "방장은 방을 나갈 수 없습니다."),
	INVITE_NOT_FOUND(HttpStatus.NOT_FOUND, "초대 코드를 찾을 수 없습니다."),
	TRIP_FULL(HttpStatus.CONFLICT, "참여 인원이 가득 찼습니다."),
	KICKED_MEMBER(HttpStatus.FORBIDDEN, "참여할 수 없는 여행방이에요."),
	TRIP_TOO_LONG(HttpStatus.BAD_REQUEST, "여행 기간이 너무 길어요."),
	TRIP_PERIOD_HAS_ITEMS(HttpStatus.CONFLICT, "기간을 줄이면 삭제되는 일정이 있습니다."),
	PIN_COLOR_TAKEN(HttpStatus.CONFLICT, "다른 멤버가 쓰고 있는 색입니다."),
	MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "멤버를 찾을 수 없습니다."),

	// 장소 카드
	PLACE_NOT_FOUND(HttpStatus.NOT_FOUND, "장소 카드를 찾을 수 없습니다."),
	PLACE_DUPLICATE(HttpStatus.CONFLICT, "이미 등록된 장소입니다."),
	NOT_GOOGLE_MAPS_URL(HttpStatus.BAD_REQUEST, "장소는 구글맵 링크로만 등록할 수 있어요."),
	PLACE_RESOLVE_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "링크에서 장소 정보를 가져오지 못했습니다."),
	GOOGLE_KEY_MISSING(HttpStatus.SERVICE_UNAVAILABLE, "서버에 구글 API 키가 설정되지 않아 장소를 등록할 수 없습니다."),
	REFERENCE_NOT_FOUND(HttpStatus.NOT_FOUND, "참고자료를 찾을 수 없습니다."),

	// 일정
	SCHEDULE_READ_ONLY(HttpStatus.FORBIDDEN, "다른 멤버의 개인 일정은 볼 수만 있습니다."),
	SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND, "일정을 찾을 수 없습니다."),
	SCHEDULE_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "일정 항목을 찾을 수 없습니다."),
	SCHEDULE_OVERLAP(HttpStatus.CONFLICT, "해당 시간에 이미 일정이 있습니다."),
	SCHEDULE_DUPLICATE(HttpStatus.CONFLICT, "해당 카드가 이미 배치되어 있습니다."),
	SCHEDULE_GAP_MISMATCH(HttpStatus.CONFLICT, "이 자리에는 넣을 수 없는 시간이에요."),
	SCHEDULE_ORDER_INVALID(HttpStatus.CONFLICT, "일정이 바뀌어 순서를 적용할 수 없습니다. 다시 시도해 주세요."),
	INVALID_DAY(HttpStatus.BAD_REQUEST, "여행 기간을 벗어난 Day입니다."),
	INVALID_TIME_RANGE(HttpStatus.BAD_REQUEST, "종료 시간은 시작 시간보다 늦어야 합니다."),

	// 채팅
	SHARE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "이 여행방의 장소 카드만 채팅에 올릴 수 있습니다.");

	private final HttpStatus status;
	private final String defaultMessage;

	ErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	public HttpStatus status() {
		return status;
	}

	public String defaultMessage() {
		return defaultMessage;
	}
}
