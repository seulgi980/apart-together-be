package com.aparttogether.apart_together_be.place;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.aparttogether.apart_together_be.config.AppProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 구글 Places API (New) 호출.
 *  - 키는 GOOGLE_MAPS_API_KEY 환경 변수로만 받는다. 브라우저에는 내려가지 않는다.
 *  - 키가 없거나, 호출이 실패하거나, 하루 한도를 넘으면 빈 값을 돌려준다.
 *    부르는 쪽은 그때 링크 주소에서 뽑은 값(장소명, 좌표)만으로 계속한다.
 *  - 구글 약관상 장소 ID 말고는 오래 저장할 수 없으므로, 평점/영업시간/웹사이트는 DB에 넣지 않고
 *    상세 화면을 열 때마다 조회한다.
 */
@Component
public class GooglePlacesClient {

	/** 텍스트 검색으로 찾은 장소. area: 카드에 보이는 짧은 지역명 (예: 아사쿠사) */
	public record FoundPlace(String placeId, String name, String address, String area, BigDecimal latitude,
			BigDecimal longitude, Place.Category category) {
	}

	/** 같은 장소의 영어 이름, 주소, 짧은 지역명. 없는 값은 null이다. */
	public record EnglishText(String name, String address, String area) {
	}

	/**
	 * 상세 패널 위쪽 3칸에 보여줄 정보. 없는 값은 null이다.
	 *
	 * @param weekdayHours 요일별 영업시간 문구 (예: "월요일: 오전 9:00 ~ 오후 5:00")
	 */
	public record PlaceInfo(Double rating, String websiteUrl, List<String> weekdayHours) {

		public static PlaceInfo empty() {
			return new PlaceInfo(null, null, List.of());
		}
	}

	private static final Logger log = LoggerFactory.getLogger(GooglePlacesClient.class);
	private static final String BASE_URL = "https://places.googleapis.com/v1/";
	private static final String SEARCH_FIELDS = "places.id,places.displayName,places.formattedAddress,"
			+ "places.location,places.primaryType,places.types,places.addressComponents";
	/** 짧은 지역명으로 쓸 주소 구성 요소. 앞에 있을수록 먼저 쓴다 (동네 -> 구 -> 시). */
	private static final List<String> AREA_TYPES = List.of("sublocality_level_2", "neighborhood",
			"sublocality_level_1", "sublocality", "locality", "administrative_area_level_2",
			"administrative_area_level_1");
	private static final String INFO_FIELDS = "id,rating,websiteUri,regularOpeningHours.weekdayDescriptions";

	private static final Set<String> CAFE = Set.of("cafe", "coffee_shop", "bakery", "tea_house", "dessert_shop",
			"ice_cream_shop");
	private static final Set<String> LODGING = Set.of("lodging", "hotel", "hostel", "guest_house", "motel",
			"resort_hotel", "japanese_inn", "bed_and_breakfast");
	private static final Set<String> SHOPPING = Set.of("shopping_mall", "department_store", "market", "store",
			"clothing_store", "gift_shop", "convenience_store", "supermarket", "book_store", "electronics_store");
	private static final Set<String> SIGHTSEEING = Set.of("tourist_attraction", "museum", "park", "art_gallery",
			"amusement_park", "aquarium", "zoo", "historical_landmark", "place_of_worship", "observation_deck",
			"national_park", "garden", "cultural_landmark", "monument", "beach", "buddhist_temple", "hindu_temple",
			"shinto_shrine", "church", "mosque", "synagogue", "castle", "historical_place", "plaza", "botanical_garden",
			"visitor_center", "tourist_information_center", "performing_arts_theater", "stadium");

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final JsonMapper json = JsonMapper.builder().build();
	private final String apiKey;

	public GooglePlacesClient(AppProperties properties) {
		String key = properties.places() == null ? null : properties.places().googleApiKey();
		this.apiKey = key == null ? "" : key.trim();
	}

	public boolean isEnabled() {
		return !apiKey.isEmpty();
	}

	/** 장소명(과 좌표)으로 구글 장소를 찾는다. 좌표가 있으면 그 근처에서 찾는다. */
	public Optional<FoundPlace> find(String name, BigDecimal latitude, BigDecimal longitude) {
		if (!isEnabled() || name == null || name.isBlank()) {
			return Optional.empty();
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("textQuery", name);
		body.put("languageCode", "ko");
		body.put("maxResultCount", 1);
		if (latitude != null && longitude != null) {
			body.put("locationBias", Map.of("circle",
					Map.of("center", Map.of("latitude", latitude, "longitude", longitude), "radius", 500.0)));
		}
		HttpRequest request = request("places:searchText", SEARCH_FIELDS)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
				.build();
		return send(request).flatMap(GooglePlacesClient::parseFound);
	}

	/** 장소 ID로 영어 이름, 주소, 지역명을 받는다. 조회하지 못하면 빈 값. */
	public Optional<EnglishText> english(String placeId) {
		if (!isEnabled()) {
			return Optional.empty();
		}
		HttpRequest request = request(
				"places/" + URLEncoder.encode(placeId, StandardCharsets.UTF_8) + "?languageCode=en",
				"displayName,formattedAddress,addressComponents")
				.GET().build();
		return send(request).map(GooglePlacesClient::parseEnglish);
	}

	/** 평점, 영업시간, 웹사이트. 조회하지 못하면 빈 값. */
	public Optional<PlaceInfo> info(String placeId) {
		if (!isEnabled()) {
			return Optional.empty();
		}
		HttpRequest request = request(
				"places/" + URLEncoder.encode(placeId, StandardCharsets.UTF_8) + "?languageCode=ko", INFO_FIELDS)
				.GET().build();
		return send(request).map(GooglePlacesClient::parseInfo);
	}

	private HttpRequest.Builder request(String path, String fieldMask) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE_URL + path))
				.timeout(Duration.ofSeconds(6))
				.header("X-Goog-Api-Key", apiKey);
		if (fieldMask != null) {
			builder.header("X-Goog-FieldMask", fieldMask);
		}
		return builder;
	}

	private Optional<JsonNode> send(HttpRequest request) {
		try {
			HttpResponse<String> response = http.send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() != 200) {
				// 키 오류, 지원하지 않는 API, 하루 한도 초과 등. 키는 로그에 남기지 않는다.
				log.warn("구글 Places 호출 실패: HTTP {} {}", response.statusCode(), errorMessage(response.body()));
				return Optional.empty();
			}
			return Optional.of(json.readTree(response.body()));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		} catch (Exception e) {
			log.warn("구글 Places 호출 실패: {}", e.toString());
			return Optional.empty();
		}
	}

	private String errorMessage(String body) {
		try {
			return json.readTree(body).path("error").path("message").asString("");
		} catch (RuntimeException e) {
			return "";
		}
	}

	/* ───────── 응답 해석 ───────── */

	static Optional<FoundPlace> parseFound(JsonNode response) {
		JsonNode place = response.path("places").path(0);
		String id = text(place.path("id"));
		String name = text(place.path("displayName").path("text"));
		if (id == null || name == null) {
			return Optional.empty();
		}
		JsonNode location = place.path("location");
		BigDecimal latitude = location.path("latitude").isNumber() ? coordinate(location.path("latitude")) : null;
		BigDecimal longitude = location.path("longitude").isNumber() ? coordinate(location.path("longitude")) : null;
		List<String> types = new ArrayList<>();
		String primary = text(place.path("primaryType"));
		if (primary != null) {
			types.add(primary);
		}
		place.path("types").forEach(type -> types.add(type.asString("")));
		return Optional.of(new FoundPlace(id, limit(name, 200), limit(text(place.path("formattedAddress")), 300),
				area(place.path("addressComponents")), latitude, longitude, category(types)));
	}

	static EnglishText parseEnglish(JsonNode place) {
		return new EnglishText(limit(text(place.path("displayName").path("text")), 200),
				limit(text(place.path("formattedAddress")), 300),
				area(place.path("addressComponents")));
	}

	/** 주소 구성 요소에서 짧은 지역명을 고른다. 없으면 null. */
	static String area(JsonNode components) {
		for (String wanted : AREA_TYPES) {
			for (JsonNode component : components) {
				for (JsonNode type : component.path("types")) {
					if (wanted.equals(type.asString(""))) {
						String name = text(component.path("longText"));
						if (name != null) {
							return limit(name, 50);
						}
					}
				}
			}
		}
		return null;
	}

	static PlaceInfo parseInfo(JsonNode place) {
		List<String> hours = new ArrayList<>();
		place.path("regularOpeningHours").path("weekdayDescriptions").forEach(line -> hours.add(line.asString("")));
		JsonNode rating = place.path("rating");
		return new PlaceInfo(
				rating.isNumber() ? rating.asDouble() : null,
				text(place.path("websiteUri")),
				hours);
	}

	/** 구글의 장소 유형 -> 우리 카테고리. 앞에 있는 유형(대표 유형)을 먼저 본다. */
	static Place.Category category(List<String> types) {
		for (String type : types) {
			if (CAFE.contains(type)) {
				return Place.Category.CAFE;
			}
			if (type.equals("restaurant") || type.endsWith("_restaurant") || type.equals("bar")
					|| type.equals("food_court") || type.equals("meal_takeaway")) {
				return Place.Category.RESTAURANT;
			}
			if (LODGING.contains(type)) {
				return Place.Category.LODGING;
			}
			if (SIGHTSEEING.contains(type)) {
				return Place.Category.SIGHTSEEING;
			}
			if (SHOPPING.contains(type) || type.endsWith("_store")) {
				return Place.Category.SHOPPING;
			}
		}
		return Place.Category.ETC;
	}

	private static BigDecimal coordinate(JsonNode node) {
		return BigDecimal.valueOf(node.asDouble()).setScale(6, RoundingMode.HALF_UP);
	}

	private static String text(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		String value = node.asString("").trim();
		return value.isEmpty() ? null : value;
	}

	private static String limit(String value, int maxLength) {
		return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
	}
}
