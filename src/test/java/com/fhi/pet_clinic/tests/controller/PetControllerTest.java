package com.fhi.pet_clinic.tests.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhi.pet_clinic.fixtures_fmwk.annotation.Fixtures;
import com.fhi.pet_clinic.fixtures_fmwk.springfixtureloader.annotation.SpringIntegrationTest;
import com.fhi.pet_clinic.model.Owner;
import com.fhi.pet_clinic.model.Pet;
import com.fhi.pet_clinic.model.Species;

import lombok.extern.slf4j.Slf4j;


/**
 * Integration tests for the PetController
 * Loads the database with test fixtures 
 * Run with:
 * $ mvn clean test -Dtest=PetControllerTest
 */

 // Declares a Spring Boot integration test with transactional fixture-based data loading
@SpringIntegrationTest

// Loads the JSON fixtures for the given entities, in the given order.
// With PER_CLASS lifecycle, fixtures are loaded once per class and cleaned via rollback.
@Fixtures(value = { Species.class, Owner.class, Pet.class }, 
          lifecycle = Fixtures.Lifecycle.PER_CLASS)
@Slf4j
public class PetControllerTest 
{
    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @DisplayName("Mate 2 pets together via POST endpoint and print the litter.")
    @Test
    void mateTwoPetsViaController_shouldReturnLitter() throws Exception 
    {
        long motherId = 4L;
        long fatherId = 2L;

        String url = String.format("/api/pets/mate?motherId=%d&fatherId=%d", motherId, fatherId);

        String json = mockMvc.perform(post(url))
                             .andExpect(status().isOk())
                             .andReturn().getResponse().getContentAsString();

        log.info("Raw JSON response:\n{}", json);

        String pretty = objectMapper.writerWithDefaultPrettyPrinter()
                                    .writeValueAsString(objectMapper.readValue(json, Object.class));
        log.info("Pretty-printed JSON response:\n{}", pretty);

        List<Pet> litter = objectMapper.readValue(json, new TypeReference<List<Pet>>() {});
        log.info("Mating result: {} offspring(s) created", litter.size());
        logLitter(litter);

        assertThat(litter).allSatisfy(pet ->
                assertThat(pet.getName()).isNotBlank());
    }

    @DisplayName("Create a new pet via POST endpoint")
    @Test
    void createPet_shouldReturnCreatedPet() throws Exception {
        Pet newPet = new Pet();
        newPet.setName("Fido");
        //newPet.setSpecies(new Species("Dog"));

        String json = objectMapper.writeValueAsString(newPet);

        mockMvc.perform(post("/api/pets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Fido"))
                .andExpect(jsonPath("$.species.name").value("Dog"));
    }

/*
    @DisplayName("Get all pets via GET endpoint")
    @Test
    void getAllPets_shouldReturnListOfPets() throws Exception 
    {
        mockMvc.perform(get("/api/pets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @DisplayName("Get a pet by ID via GET endpoint")
    @Test
    void getPetById_shouldReturnPet() throws Exception {
        long petId = 1L; // Assume this ID exists in the fixtures

        mockMvc.perform(get("/api/pets/{id}", petId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(petId));
    }

    @DisplayName("Update a pet via PUT endpoint")
    @Test
    void updatePet_shouldReturnUpdatedPet() throws Exception {
        long petId = 1L; // Assume this ID exists in the fixtures

        Pet updatedPet = new Pet();
        updatedPet.setName("Tiger");
        updatedPet.setSpecies(new Species("Cat"));

        String json = objectMapper.writeValueAsString(updatedPet);

        mockMvc.perform(put("/api/pets/{id}", petId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tiger"))
                .andExpect(jsonPath("$.species.name").value("Cat"));
    }

    @DisplayName("Delete a pet via DELETE endpoint")
    @Test
    void deletePet_shouldReturnNoContent() throws Exception {
        long petId = 1L; // Assume this ID exists in the fixtures

        mockMvc.perform(delete("/api/pets/{id}", petId))
                .andExpect(status().isNoContent());
    }
*/
    private void logLitter(List<Pet> litter) {
        litter.forEach(pet -> log.info(" - name={}, sex={}, coatColor={}, degeneracy={}, sterile={}",
                pet.getName(),
                pet.getSex(),
                pet.getCoatColor(),
                pet.getDegeneracyScore(),
                pet.getSterile()));
    }

}

