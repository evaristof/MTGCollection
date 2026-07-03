package com.evaristof.mtgcollection.repository;

import com.evaristof.mtgcollection.domain.CardImageHash;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CardImageHashRepository extends JpaRepository<CardImageHash, Long> {
    Optional<CardImageHash> findBySetCodeAndCollectorNumber(String setCode, String collectorNumber);

    List<CardImageHash> findBySetCode(String setCode);
}
