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

    /**
     * All stacks matching set + number + foil + language. There can be more than
     * one when the same printing is stored in different locations, so the add
     * flow filters these by {@code localizacao} to merge into the right stack.
     */
    List<CollectionCard> findAllBySetCodeAndCardNumberAndFoilAndLanguage(
            String setCode, String cardNumber, boolean foil, String language);
}
