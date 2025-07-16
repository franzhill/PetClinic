package com.fhi.pet_clinic.tests.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhi.pet_clinic.fixtures_fmwk.Fixtures;
import com.fhi.pet_clinic.fixtures_fmwk.springfixtureloader.SpringFixtureTest;
import com.fhi.pet_clinic.model.Owner;
import com.fhi.pet_clinic.model.Pet;
import com.fhi.pet_clinic.model.Species;

import lombok.extern.slf4j.Slf4j;

/**
 * Integration test for mating pets.
 *
 * Loads the database with test fixtures and attempts to mate two pets,
 * printing the resulting offspring via logger.
 * 
 * Run with:
 * $ mvn clean test -Dtest=PetControllerTest
 */

 // Declares a Spring Boot integration test with transactional fixture-based data loading
@SpringFixtureTest

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
        long motherId = 2L; // Whiskers (FEMALE)
        long fatherId = 1L; // Toto (MALE)

        String url = String.format("/api/pets/mate?motherId=%d&fatherId=%d", motherId, fatherId);

        String json = mockMvc.perform(post(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
/*

        String requestBody = String.format("{ \"motherId\": %d, \"fatherId\": %d }", motherId, fatherId);

        String json = mockMvc.perform(post("/api/pets/mate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
*/
        log.info("Raw JSON response:\n{}", json);  // 👈 added

        List<Pet> litter = objectMapper.readValue(json, new TypeReference<List<Pet>>() {});

        log.info("Mating result: {} offspring(s) created", litter.size());
        logLitter(litter);

        assertThat(litter).allSatisfy(pet ->
                assertThat(pet.getName()).isNotBlank());
    }

    private void logLitter(List<Pet> litter) {
        litter.forEach(pet -> log.info(" - name={}, sex={}, coatColor={}, degeneracy={}, sterile={}",
                pet.getName(),
                pet.getSex(),
                pet.getCoatColor(),
                pet.getDegeneracyScore(),
                pet.getSterile()));
    }
}
