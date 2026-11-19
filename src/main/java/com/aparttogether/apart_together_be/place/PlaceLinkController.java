package com.aparttogether.apart_together_be.place;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aparttogether.apart_together_be.place.PlaceLinkResolver.ResolvedPlace;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 구글맵 링크를 장소 정보로 바꾼다. 등록 확인 창을 채우는 데 쓴다. (로그인 필요) */
@RestController
@RequestMapping("/api/places")
public class PlaceLinkController {

	public record ResolveRequest(@NotBlank @Size(max = 2048) String url) {
	}

	private final PlaceLinkResolver resolver;

	public PlaceLinkController(PlaceLinkResolver resolver) {
		this.resolver = resolver;
	}

	@PostMapping("/resolve")
	public ResolvedPlace resolve(@Valid @RequestBody ResolveRequest request) {
		return resolver.resolve(request.url());
	}

}
