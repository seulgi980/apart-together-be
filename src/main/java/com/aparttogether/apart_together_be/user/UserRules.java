package com.aparttogether.apart_together_be.user;

/** 가입 입력 규칙 (F1-1). 프론트 features/auth/validation.ts와 같은 규칙이다. */
public final class UserRules {

	private UserRules() {
	}

	public static final String LOGIN_ID = "^[a-z0-9_]{4,16}$";
	public static final String LOGIN_ID_HELP = "영문 소문자, 숫자, 밑줄(_) 4~16자";

	public static final String NICKNAME = "^[가-힣a-zA-Z0-9]{2,10}$";
	public static final String NICKNAME_HELP = "한글, 영문, 숫자 2~10자 (특수문자 불가)";

	public static final String PASSWORD = "^[A-Za-z0-9!@#$%^&*]{8,20}$";
	public static final String PASSWORD_HELP = "영문, 숫자, 특수문자(!@#$%^&*) 8~20자";
}
