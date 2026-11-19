package com.aparttogether.apart_together_be.place;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.aparttogether.apart_together_be.common.ApiException;
import com.aparttogether.apart_together_be.common.ErrorCode;

/**
 * 구글맵 공유 링크 -> 장소 정보 (F2-1, F2-2).
 *  1) 짧은 링크(maps.app.goo.gl 등)는 리다이렉트를 따라가 긴 주소를 얻는다. (구글 주소로만 따라간다)
 *  2) 긴 주소에서 검색어로 쓸 장소명과 좌표를 뽑는다.
 *     (공유 링크를 장소 ID로 바꿔 주는 구글 API는 없어서, 이 단계만 주소를 직접 읽는다)
 *  3) 장소명과 좌표로 구글 장소를 찾는다 (Places API Text Search).
 *     장소 ID, 이름, 주소, 지역명, 좌표, 카테고리는 모두 구글이 준 값을 쓴다. 장소 ID가 place_key(중복 판단 기준)다.
 *  4) 같은 장소를 영어로 한 번 더 조회해 영어 이름·주소를 받는다 (Place Details).
 *
 * 구글 키가 없거나 구글에서 장소를 찾지 못하면 등록하지 않고 오류를 돌려준다.
 * (링크에서 읽은 값만으로 대신 등록하지 않는다)
 */
@Service
public class PlaceLinkResolver {

	/**
	 * nameEn, addressEn: 영어 이름과 주소. 한국어 값과 같거나 받지 못하면 null.
	 * category: 구글이 알려준 유형으로 고른 카테고리.
	 */
	public record ResolvedPlace(String placeKey, String name, String nameEn, String address, String addressEn, String area,
			BigDecimal latitude, BigDecimal longitude, String googleMapsUrl, Place.Category category) {
	}

	/** 긴 주소에서 뽑은 검색어(장소명)와 좌표 */
	record Parsed(String name, BigDecimal latitude, BigDecimal longitude) {
	}

	private static final Logger log = LoggerFactory.getLogger(PlaceLinkResolver.class);
	private static final int MAX_REDIRECTS = 6;

	private static final Pattern PLACE_NAME = Pattern.compile("/maps/place/([^/@?]+)");
	private static final Pattern PIN = Pattern.compile("!3d(-?\\d+(?:\\.\\d+)?)!4d(-?\\d+(?:\\.\\d+)?)");
	private static final Pattern VIEWPORT = Pattern.compile("@(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)");
	private static final Pattern QUERY = Pattern.compile("[?&]q=([^&]+)");
	private static final Pattern COORDINATE_QUERY = Pattern.compile("^(-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)$");

	private final HttpClient http = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(Duration.ofSeconds(5))
			.build();
	private final GooglePlacesClient google;

	public PlaceLinkResolver(GooglePlacesClient google) {
		this.google = google;
	}

	public ResolvedPlace resolve(String input) {
		String url = input == null ? "" : input.trim();
		if (!GoogleMapsUrls.isGoogleMapsUrl(url)) {
			throw new ApiException(ErrorCode.NOT_GOOGLE_MAPS_URL);
		}
		if (!google.isEnabled()) {
			throw new ApiException(ErrorCode.GOOGLE_KEY_MISSING);
		}
		Parsed parsed = parse(followRedirects(url));
		if (parsed.name() == null) {
			throw new ApiException(ErrorCode.PLACE_RESOLVE_FAILED);
		}
		GooglePlacesClient.FoundPlace found = google.find(parsed.name(), parsed.latitude(), parsed.longitude())
				.orElseThrow(() -> new ApiException(ErrorCode.PLACE_RESOLVE_FAILED));
		// 영어 이름과 주소는 같은 장소를 영어로 한 번 더 물어서 받는다
		GooglePlacesClient.EnglishText english = google.english(found.placeId()).orElse(null);
		return new ResolvedPlace(
				found.placeId(),
				found.name(),
				english == null ? null : differentFrom(english.name(), found.name()),
				found.address(),
				english == null ? null : differentFrom(english.address(), found.address()),
				found.area(),
				found.latitude(), found.longitude(), url,
				found.category());
	}

	/** 한국어 값과 같으면 두 번 보여줄 필요가 없으므로 null */
	private static String differentFrom(String english, String korean) {
		return english == null || english.equalsIgnoreCase(korean) ? null : english;
	}

	/** 긴 구글맵 주소에서 검색어로 쓸 장소명과 좌표를 뽑는다 */
	static Parsed parse(String url) {
		String name = null;
		Matcher place = PLACE_NAME.matcher(url);
		if (place.find()) {
			name = decode(place.group(1));
		}
		BigDecimal latitude = null;
		BigDecimal longitude = null;
		// !3d!4d는 핀의 좌표, @는 화면 중심이다. 핀 좌표를 먼저 쓴다.
		Matcher pin = PIN.matcher(url);
		Matcher viewport = VIEWPORT.matcher(url);
		if (pin.find()) {
			latitude = coordinate(pin.group(1));
			longitude = coordinate(pin.group(2));
		} else if (viewport.find()) {
			latitude = coordinate(viewport.group(1));
			longitude = coordinate(viewport.group(2));
		}
		Matcher query = QUERY.matcher(url);
		if (query.find()) {
			String q = decode(query.group(1));
			Matcher coordinates = COORDINATE_QUERY.matcher(q);
			if (coordinates.matches()) {
				if (latitude == null) {
					latitude = coordinate(coordinates.group(1));
					longitude = coordinate(coordinates.group(2));
				}
			} else if (name == null && !q.isBlank()) {
				// 모바일 공유 링크: q=장소명, 주소
				name = q.contains(",") ? q.substring(0, q.indexOf(',')).trim() : q.trim();
			}
		}
		if (latitude != null && (latitude.abs().compareTo(BigDecimal.valueOf(90)) > 0
				|| longitude.abs().compareTo(BigDecimal.valueOf(180)) > 0)) {
			latitude = null;
			longitude = null;
		}
		if (name != null && name.length() > 200) {
			name = name.substring(0, 200);
		}
		return new Parsed(name, latitude, longitude);
	}

	private static BigDecimal coordinate(String text) {
		return new BigDecimal(text).setScale(6, RoundingMode.HALF_UP);
	}

	private static String decode(String text) {
		try {
			return URLDecoder.decode(text, StandardCharsets.UTF_8).trim();
		} catch (IllegalArgumentException e) {
			return text.replace('+', ' ').trim();
		}
	}

	/** 리다이렉트를 따라간다. 구글맵 주소가 아닌 곳으로는 가지 않는다 (서버가 임의 주소를 부르지 않게). */
	private String followRedirects(String url) {
		String current = url;
		for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
			if (current.contains("/maps/place/") || current.contains("ftid=") || current.contains("?q=")) {
				return current;
			}
			try {
				HttpRequest request = HttpRequest.newBuilder(URI.create(current))
						.timeout(Duration.ofSeconds(5))
						.header("Accept-Language", "ko")
						.GET().build();
				HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
				int status = response.statusCode();
				String location = response.headers().firstValue("location").orElse(null);
				if (status < 300 || status >= 400 || location == null) {
					return current;
				}
				String next = URI.create(current).resolve(location).toString();
				if (!isGoogleHost(next)) {
					return current;
				}
				current = next;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new ApiException(ErrorCode.PLACE_RESOLVE_FAILED);
			} catch (Exception e) {
				log.info("구글맵 링크를 열지 못했습니다: {}", e.toString());
				throw new ApiException(ErrorCode.PLACE_RESOLVE_FAILED);
			}
		}
		return current;
	}

	private static boolean isGoogleHost(String url) {
		try {
			URI uri = URI.create(url);
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
			return "https".equals(uri.getScheme()) && (host.equals("google.com") || host.endsWith(".google.com")
					|| host.equals("google.co.kr") || host.endsWith(".google.co.kr") || host.equals("goo.gl")
					|| host.endsWith(".goo.gl") || host.equals("g.co"));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
