
package com.fhi.pet_clinic.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhi.pet_clinic.dto.OwnerDto;
import com.fhi.pet_clinic.fixtures.FixtureExtension;
import com.fhi.pet_clinic.fixtures.Fixtures;
import com.fhi.pet_clinic.model.Owner;
import com.fhi.pet_clinic.model.Pet;
import com.fhi.pet_clinic.repo.OwnerRepository;
import com.fhi.pet_clinic.repo.PetRepository;

import lombok.extern.slf4j.Slf4j;

// Starts the full application context (like a mini Spring Boot app) for testing
// when the test class is initialized.
// So once per test class, not before each test.
// That context is shared across all test methods in the class.
@SpringBootTest

// Automatically configures MockMvc for testing HTTP endpoints
@AutoConfigureMockMvc

// Load the fixtures for the given entities, in the given order.
@Fixtures({ Owner.class, Pet.class })
@ExtendWith(FixtureExtension.class)

@Slf4j
class OwnerControllerIntegrationTest 
{
   @Autowired
   private MockMvc mockMvc;

   @Autowired
   private ObjectMapper objectMapper;

    @Autowired
    private OwnerRepository ownerRepository;

    @Autowired
    private PetRepository petRepository;


   @BeforeEach
   void setup() 
   {  // runs before each @Test, but uses the same application context
   }


    @DisplayName("Verify that owners and pets are loaded from fixtures")
    @Test
    void printLoadedFixtures() 
    {   log.debug("");
        // GIVEN:
        // The test context has been bootstrapped with fixture data (via @Fixtures annotation)

        // WHEN:
        // The test is run

        // THEN:
        // The test context should have been loaded with fixtures and everything
        log.info("\n=== Loaded Owners ===");
        for (Owner o : ownerRepository.findAll()) 
        {   log.info("Owner[id={}, name={}]", o.getId(), o.getName());
        }

        log.info("\n=== Loaded Pets ===");
        for (Pet p : petRepository.findAll()) 
        {   log.info("Pet[id={}, name={}, owner={}]", p.getId(), p.getName(), p.getOwner() != null ? p.getOwner().getName() : "null");
        }
    }



   //@Test
   void testCreateOwner() throws Exception 
   {
      // GIVEN:
      // Owner creation payload
      OwnerDto ownerDto    =  new OwnerDto();
      String   jsonPayload = objectMapper.writeValueAsString(ownerDto);

      // WHEN:
      // Call the endpoint 
      mockMvc.perform(post("/owners")
               .contentType(MediaType.APPLICATION_JSON)
               .content(jsonPayload))
               .andExpect(status().isCreated());

      // THEN:       
      // Add assertions to verify the result, like fetching the created owner
   }


   //@Test
   void testDeleteOwner() throws Exception 
   {
      Long ownerId = 1L;
      // First, ensure the owner with ownerId exists

      mockMvc.perform(delete("/owners/{id}", ownerId))
               .andExpect(status().isNoContent());
      // Add assertions to verify the owner has been deleted
   }

   // ... additional tests can go here ...
}
