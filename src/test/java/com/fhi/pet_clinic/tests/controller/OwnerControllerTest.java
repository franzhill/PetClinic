
package com.fhi.pet_clinic.tests.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhi.pet_clinic.dto.OwnerDto;
import com.fhi.pet_clinic.fixtures_fmwk.Fixtures;
import com.fhi.pet_clinic.fixtures_fmwk.junitfixtureloader.FixtureExtension;
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

// This tells Spring to destroy and recreate the entire application context after the test class.
// It’s heavy (slow), but guarantees a clean state.
// Use case:
// - You don’t want to write manual purging logic.
// - You're OK with slower test startup.
//# @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)

// Also remember that:
// If using an in-memory H2 database (which is often the case for Integration testing)
// with spring.jpa.hibernate.ddl-auto=create-drop, then:
// - the database is created from scratch before each test class
// - it is automatically dropped afterward

// Ensures JUnit uses a single instance of the test class => the same instance is reused
// for all test methods, which enables @BeforeAll methods to be non-static (and lets 
// you carry state between tests if needed).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)

// Ensures tests run in a defined order if needed
// By placing 
// @Order(n)
// on a test.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)

// Automatically configures MockMvc for testing HTTP endpoints
@AutoConfigureMockMvc

// Loads the fixtures for the given entities, in the given order.
@Fixtures({ Owner.class, Pet.class })

// Extends the JUnit 5 test lifecycle to provide custom behavior during test execution —
// Here we're extending with the custom defined fixture mechanism, and read the @Fixtures
// annotation to handle fixture loading seamlessly.
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
