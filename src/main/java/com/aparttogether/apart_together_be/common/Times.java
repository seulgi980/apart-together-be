package com.aparttogether.apart_together_be.common;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/** 시간 변환. 일정 시간은 현지 시각 "HH:mm" 그대로 다룬다 (시간대 변환 없음, 1분 단위). */
public final class Times {

	private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

	private Times() {
	}

	/** DATETIME 컬럼은 초 단위라서 초까지만 남긴다 */
	public static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.SECONDS);
	}

	public static String format(LocalTime time) {
		return time == null ? null : time.format(HHMM);
	}

	/** "HH:mm" -> LocalTime. null이나 빈 값은 null. 형식이 틀리면 VALIDATION_FAILED. */
	public static LocalTime parse(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		try {
			return LocalTime.parse(text.trim(), HHMM);
		} catch (DateTimeParseException e) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "시간은 HH:mm 형식이어야 합니다.");
		}
	}

	public static int minutes(LocalTime time) {
		return time.getHour() * 60 + time.getMinute();
	}
}
