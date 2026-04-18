package com.evaristof.mtgcollection.repository;

import com.evaristof.mtgcollection.domain.MagicSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MagicSetRepository extends JpaRepository<MagicSet, String> {
}
