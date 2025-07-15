package com.fhi.pet_clinic.tests.fixtures;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import com.fhi.pet_clinic.fixtures_fmwk.Fixtures;
import com.fhi.pet_clinic.fixtures_fmwk.springfixtureloader.SpringFixtureTest;
import com.fhi.pet_clinic.model.Owner;
import com.fhi.pet_clinic.model.Pet;
import com.fhi.pet_clinic.model.Species;
import com.fhi.pet_clinic.repo.OwnerRepository;
import com.fhi.pet_clinic.repo.PetRepository;

import lombok.extern.slf4j.Slf4j;


// Declares a Spring Boot integration test with transactional fixture-based data loading
 @SpringFixtureTest

// Loads the JSON fixtures for the given entities, in the given order.
// With PER_CLASS lifecycle, fixtures are loaded once per class and cleaned via rollback.
 @Fixtures(value = { Species.class, Owner.class, Pet.class }, lifecycle = Fixtures.Lifecycle.PER_CLASS)

// Ensures test methods are run in a specific order using @Order(n)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)


@Slf4j
class SpringFixtureLoaderTest 
{
    @Autowired
    private OwnerRepository ownerRepository;

    @Autowired
    private PetRepository petRepository;


   @BeforeEach
   void setup() 
   {  // runs before each @Test, but uses the same application context
   }


    @DisplayName("Verify the fixture loading mechanism")
    @Test
    @Order(1)
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


    /**
     * Since we're using the Fixtures.lifecycle.PER_CLASS strategy, the fixtures should NOT
     * have been reloaded upon this second test.
     * Since they should have been purged at the end of the first test, it follows that the
     * DB should be empty.
     */
    @DisplayName("Check that fixtures are purged after the first test")
    @Test
    @Order(2)
    void shouldHavePurgedFixtures() 
    {   log.debug("\n");
        log.debug("\n=== Verifying DB cleanup ===");
        assertTrue(ownerRepository.findAll().isEmpty(), "Owners should have been purged");
        assertTrue(petRepository  .findAll().isEmpty(), "Pets should have been purged");
    }
}