package com.aparttogether.apart_together_be.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 테스트용 HTTP 호출 도우미 */
public class TestApi {

	public static final JsonMapper MAPPER = JsonMapper.builder().build();

	public record Response(int status, JsonNode body, Map<String, List<String>> headers) {

		/** Set-Cookie 헤더에서 쿠키 값을 꺼낸다. 없으면 null. */
		public String cookie(String name) {
			String header = setCookie(name);
			if (header == null) {
				return null;
			}
			return header.substring(name.length() + 1, header.contains(";") ? header.indexOf(';') : header.length());
		}

		/** Set-Cookie 헤더 전체 */
		public String setCookie(String name) {
			return headers.getOrDefault("set-cookie", List.of()).stream()
					.filter(value -> value.startsWith(name + "="))
					.findFirst().orElse(null);
		}
	}

	private final String baseUrl;
	private final HttpClient client = HttpClient.newHttpClient();

	public TestApi(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	/** json("a", 1, "b", "x") -> {"a":1,"b":"x"} */
	public static ObjectNode json(Object... pairs) {
		ObjectNode node = MAPPER.createObjectNode();
		for (int i = 0; i < pairs.length; i += 2) {
			node.set((String) pairs[i], MAPPER.valueToTree(pairs[i + 1]));
		}
		return node;
	}

	public Response get(String token, String path) {
		return send("GET", token, path, null, null);
	}

	public Response post(String token, String path, Object body) {
		return send("POST", token, path, body, null);
	}

	public Response put(String token, String path, Object body) {
		return send("PUT", token, path, body, null);
	}

	public Response patch(String token, String path, Object body) {
		return send("PATCH", token, path, body, null);
	}

	public Response delete(String token, String path) {
		return send("DELETE", token, path, null, null);
	}

	public Response send(String method, String token, String path, Object body, Map<String, String> headers) {
		try {
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path));
			if (token != null) {
				builder.header("Authorization", "Bearer " + token);
			}
			if (headers != null) {
				headers.forEach(builder::header);
			}
			if (body != null) {
				builder.header("Content-Type", "application/json");
				builder.method(method,
						HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
			} else {
				builder.method(method, HttpRequest.BodyPublishers.noBody());
			}
			HttpResponse<String> response = client.send(builder.build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			String text = response.body();
			JsonNode json = null;
			if (text != null && !text.isBlank()) {
				try {
					json = MAPPER.readTree(text);
				} catch (RuntimeException e) {
					json = MAPPER.getNodeFactory().stringNode(text);
				}
			}
			return new Response(response.statusCode(), json, response.headers().map());
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
