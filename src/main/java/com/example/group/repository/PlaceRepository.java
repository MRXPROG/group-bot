package com.example.group.repository;

import com.example.group.model.Place;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlaceRepository extends JpaRepository<Place, Long> {

    @EntityGraph(attributePaths = "city")
    List<Place> findByVisibleTrue();
}
