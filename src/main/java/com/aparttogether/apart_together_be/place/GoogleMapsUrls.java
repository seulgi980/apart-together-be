package com.aparttogether.apart_together_be.place;

import java.net.URI;
import java.util.Set;

/** 구글맵 링크 판단 (F2-1, F2-7). 프론트의 isGoogleMapsUrl과 같은 규칙이다. */
public final class GoogleMapsUrls {

	private static final Set<String> ALWAYS = Set.of("maps.app.goo.gl", "maps.google.com");
	private static final Set<String> WITH_MAPS_PATH = Set.of("goo.gl", "google.com", "www.google.com", "google.co.kr",
			"www.google.co.kr");

	private GoogleMapsUrls() {
	}

	public static boolean isGoogleMapsUrl(String input) {
		if (input == null) {
			return false;
		}
		try {
			URI uri = URI.create(input.trim());
			String scheme = uri.getScheme();
			String host = uri.getHost();
			if (host == null || scheme == null || !(scheme.equals("https") || scheme.equals("http"))) {
				return false;
			}
			host = host.toLowerCase();
			if (ALWAYS.contains(host) || host.equals("g.co")) {
				return true;
			}
			String path = uri.getPath() == null ? "" : uri.getPath();
			return WITH_MAPS_PATH.contains(host) && path.startsWith("/maps");
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
