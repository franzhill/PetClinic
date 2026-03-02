package com.fhi.pet_clinic.controller;

import com.fhi.pet_clinic.model.Species;
import com.fhi.pet_clinic.repo.SpeciesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/species")
@RequiredArgsConstructor // Automatically creates constructor for final fields
public class SpeciesController {

    private final SpeciesRepository speciesRepository;

    @PostMapping
    public ResponseEntity<Species> createSpecies(@RequestBody Species species) {
        // Idempotency check for Moxter: 
        // If "Dog" already exists, just return it so the test can proceed.
        return speciesRepository .findByName(species.getName())
                                 .map(ResponseEntity::ok)
                                 .orElseGet(() -> {
                                    Species saved = speciesRepository.save(species);
                                    return ResponseEntity.status(HttpStatus.CREATED).body(saved);
                                 });
    }

    @GetMapping
    public List<Species> getAll() {
        return speciesRepository.findAll();
    }

    @GetMapping("/name/{name}")
    public ResponseEntity<Species> getByName(@PathVariable String name) {
        return speciesRepository.findByName(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}