package com.aparttogether.apart_together_be.place;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceReferenceRepository extends JpaRepository<PlaceReference, Long> {

	List<PlaceReference> findByPlaceIdInOrderByIdAsc(Collection<Long> placeIds);

	List<PlaceReference> findByPlaceIdOrderByIdAsc(Long placeId);

	Optional<PlaceReference> findByIdAndPlaceId(Long id, Long placeId);
}
