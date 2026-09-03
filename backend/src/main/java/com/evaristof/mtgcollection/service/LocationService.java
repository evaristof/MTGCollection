package com.evaristof.mtgcollection.service;

import com.evaristof.mtgcollection.domain.Location;
import com.evaristof.mtgcollection.repository.CollectionCardRepository;
import com.evaristof.mtgcollection.repository.LocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * The physical-location catalog ({@code LOCATION}) behind the "Cadastro de
 * Localização" screen and the location field of every card write.
 *
 * <p>Single owner of "name → {@link Location}" resolution: the cards API, the
 * scanner and the spreadsheet import all send a location <em>name</em>, and
 * this service maps it to the catalog row, creating it on first use. That
 * keeps typing a brand-new location in any of those screens working exactly
 * as before the column became a FK, while still ending up with one row per
 * real-world box/binder.</p>
 */
@Service
public class LocationService {

    private final LocationRepository repository;
    private final CollectionCardRepository cardRepository;

    public LocationService(LocationRepository repository, CollectionCardRepository cardRepository) {
        this.repository = repository;
        this.cardRepository = cardRepository;
    }

    @Transactional(readOnly = true)
    public List<Location> listAll() {
        return repository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Optional<Location> findById(Long id) {
        return repository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<Location> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return repository.findByNameIgnoreCase(name.trim());
    }

    @Transactional
    public Location create(String name, String description) {
        return repository.save(new Location(name.trim(), blankToNull(description)));
    }

    @Transactional
    public Location update(Location existing, String name, String description) {
        existing.setName(name.trim());
        existing.setDescription(blankToNull(description));
        return repository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public boolean exists(Long id) {
        return repository.existsById(id);
    }

    /** How many collection cards currently sit in this location. */
    @Transactional(readOnly = true)
    public long countCards(Long locationId) {
        return cardRepository.countByLocation_Id(locationId);
    }

    /**
     * The catalog row for {@code name}, created when this is the first time
     * the user types it. Matching is case-insensitive so "caixa 3" and
     * "Caixa 3" don't become two rows.
     */
    @Transactional
    public Location findOrCreateByName(String name) {
        String trimmed = name.trim();
        return repository.findByNameIgnoreCase(trimmed)
                .orElseGet(() -> repository.save(new Location(trimmed, null)));
    }

    /**
     * Resolves the location for a card write.
     *
     * <ul>
     *   <li>{@code locationId} set → that exact row (throws when unknown).</li>
     *   <li>otherwise {@code name} non-blank → find-or-create by name.</li>
     *   <li>otherwise → {@code null} (card without a location).</li>
     * </ul>
     */
    @Transactional
    public Location resolve(Long locationId, String name) {
        if (locationId != null) {
            return repository.findById(locationId)
                    .orElseThrow(() -> new NoSuchElementException(
                            "Localização não encontrada: id=" + locationId));
        }
        if (name == null || name.isBlank()) {
            return null;
        }
        return findOrCreateByName(name);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
