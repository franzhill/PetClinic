package com.fhi.pet_clinic.moxter_tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


/**
 * Run with:
 *   mvn clean test -Dtest=MyMoxterTest
 *
 * This test inherits:
 * - SpringBootTest + MockMvc setup
 * - FixtureEngine boot + BeforeAll/AfterAll group execution
 * - Auth wiring per request
 */
class MyMoxterTest extends ParentMoxterTest 
{

@Test
    @DisplayName("Scenario 1: Create Owner -> Create Pet -> Verify Association")
    void checkCreatePet()
    {
        // 1. Create the Owner
        // The fixture 'create_owner' saves 'ownerId' into the engine's vars
        fx.callFixture("create_owner");

        // 2. Create a Pet for that Owner
        // The fixture 'create_pet_for_owner' uses {{ownerId}} in its payload
        // We use the Fluent API to preset a caller that returns the body directly
        fx.callFixture("create_pet_for_owner");

        // Simple manual assertion on the returned body
        assertNotNull(fx.varsGetLong("petId"), "Pet should have been assigned an ID");
        assertEquals("Snowy", fx.varsGetString("petName"));
        assertEquals(fx.varsGetLong("ownerId"), fx.varsGetLong("petOwnerId"));



    }
}
