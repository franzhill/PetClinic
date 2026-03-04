package com.fhi.pet_clinic.moxter_tests.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fhi.pet_clinic.moxter_tests.ParentMoxterTest;


/**
 * Run with:
 *   mvn clean test -Dtest=CrudTest
 */
class CrudTest extends ParentMoxterTest 
{

    @Test
    @DisplayName("Scenario 1: Create Owner -> Create Pet -> Verify Association")
    void checkCreatePet()
    {
        // 1. Create the Owner
        // The fixture 'create_owner' saves 'ownerId' into the engine's vars
        fx.callMoxture("create_owner");

        // 2. Create a Pet for that Owner
        // 2.1. By overriding the name
        String petName="Rex";
        fx.callMoxture("create_pet_for_owner", Map.of("in_petName", petName));

        assertNotNull(fx.varsGetLong("petId"), "Pet should have been assigned an ID");
        assertEquals(petName, fx.varsGetString("petName"));
        assertEquals(fx.varsGetLong("ownerId"), fx.varsGetLong("petOwnerId"));

        // 2.2. By using the fixture default
        fx.callMoxture("create_pet_for_owner");
        assertEquals(fx.moxVar("create_pet_for_owner", "in_petName"), fx.varsGetString("petName"));
    }
}
