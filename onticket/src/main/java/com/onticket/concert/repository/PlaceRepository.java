package com.onticket.concert.repository;

import com.onticket.concert.domain.Place;
import org.springframework.data.repository.CrudRepository;

public interface PlaceRepository extends CrudRepository<Place, String> {
    Place findByPlaceId(String placeId);

}
