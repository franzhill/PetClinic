package com.fhi.pet_clinic.repo;

import com.fhi.pet_clinic.model.Species;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpeciesRepository extends JpaRepository<Species, Long> {

    /**
     * Finds a species by its exact name (case-sensitive).
     *
     * @param name the species name, e.g. "Dog"
     * @return an Optional containing the Species if found
     */
    Optional<Species> findByName(String name);

    /**
     * Returns true if a species with the given name exists.
     */
    boolean existsByName(String name);
}
