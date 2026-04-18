package com.evaristof.mtgcollection.repository;

import com.evaristof.mtgcollection.domain.CollectionCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CollectionCardRepository extends JpaRepository<CollectionCard, Long> {

    List<CollectionCard> findBySetCode(String setCode);

    Optional<CollectionCard> findBySetCodeAndCardNumberAndFoilAndLanguage(
            String setCode, String cardNumber, boolean foil, String language);
}
