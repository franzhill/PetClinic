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
    @DisplayName("Old API - Create Owner, Pet, verify association")
    void checkCreateOwnerAndPet()
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
        assertEquals(fx.moxVar("create_pet_for_owner", "in_petName"), 
                     fx.vars().get("petName").asString());
    }



    @Test
    @DisplayName("New Fluent API - Create Owner, Pet, verify association")
    void checkCreateOwnerAndPet_FluentAPI() 
    {
        // 1. Create the Owner (saves 'ownerId' automatically)
        fx.caller().call("create_owner");
        // 2. Create a Pet for that Owner
        // 2.1. By overriding the name
        String petName = "Rex";
        fx.caller()
          .with("in_petName", petName)
          .call("create_pet_for_owner")
          .assertVar("petId"     , id   -> id.isNotNull())
          .assertVar("petName"   , name -> name.isEqualTo(petName))
          .assertVar("petOwnerId", id   -> id.isEqualTo(fx.varsGet("ownerId")));

        // 2.2. By using the fixture default ("Snowy" from your YAML)
        fx.caller()
          .call("create_pet_for_owner")
          .assertVar("petName", name -> name.isEqualTo(fx.moxVar("create_pet_for_owner", "in_petName")));
    }


    @Test
    @DisplayName("Using a group moxture - Create Owner, Pet, verify association")
    void checkCreateOwnerAndPet_Group() 
    {
        String petName = "Rex";
        fx.caller()
          .with("in_petName", petName)
          .call("create_owner_and_pet")
          .assertVar("petName", name -> name.isEqualTo(petName));
    }

}
