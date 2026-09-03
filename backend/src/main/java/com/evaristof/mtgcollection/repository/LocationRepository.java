package com.evaristof.mtgcollection.repository;

import com.evaristof.mtgcollection.domain.Location;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LocationRepository extends JpaRepository<Location, Long> {

    List<Location> findAllByOrderByNameAsc();

    Optional<Location> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);
}
