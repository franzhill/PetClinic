package com.fhi.pet_clinic.tests.tools.json_simple_fixtures;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.fhi.libraries.json_simple_fixtures.annotation.Fixtures;
import com.fhi.libraries.json_simple_fixtures.junitfixtureloader.FixtureExtension;
import com.fhi.pet_clinic.annotation.MetaSpringBootTestWithJsonSimpleFixtures;
import com.fhi.pet_clinic.model.Owner;
import com.fhi.pet_clinic.model.Pet;
import com.fhi.pet_clinic.repo.OwnerRepository;
import com.fhi.pet_clinic.repo.PetRepository;

import lombok.extern.slf4j.Slf4j;


/**
 * Run with
 * $ mvn clean test -Dtest=JUnitFixtureLoaderTest
 */


// Extends the JUnit 5 test lifecycle to provide custom behavior during test execution —
// Here we're extending with the custom defined fixture mechanism, and read the @Fixtures
// annotation to handle fixture loading seamlessly.
@ExtendWith(FixtureExtension.class)

// Loads the fixtures for the given entities, in the given order.
@Fixtures(value = { Owner.class, Pet.class }, lifecycle = Fixtures.Lifecycle.PER_METHOD)

@Slf4j
class JUnitFixtureLoaderTest 
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
}