package com.fhi.pet_clinic.moxter_tests.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.log;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fhi.pet_clinic.moxter_tests.ParentMoxterTest;

import lombok.extern.slf4j.Slf4j;


/**
 * Run with:
 *   mvn clean test -Dtest=CrudTest
 */
@Slf4j
class CrudTest extends ParentMoxterTest 
{
/* DEPRECATED
    @Test
    @DisplayName("Old APIs - Create Owner, Pet, verify association")
    void checkCreateOwnerAndPet()
    {
        // 1. Create the Owner
        // The fixture 'create_owner' saves 'ownerId' into the engine's vars
        mx.callMoxture("create_owner");

        // 2. Create a Pet for that Owner
        // 2.1. By overriding the name
        String petName="Rex";
        mx.callMoxture("create_pet_for_owner", Map.of("in_petName", petName));

        assertNotNull(mx.vars().get("petId"), "Pet should have been assigned an ID");
        assertEquals(petName, mx.vars().get("petName"));
        assertEquals(mx.vars().get("ownerId"), mx.vars().get("petOwnerId"));

        // 2.2. By using the fixture default
        mx.callMoxture("create_pet_for_owner");
        assertEquals(mx.moxVar("create_pet_for_owner", "in_petName"), 
                     mx.vars().read("petName").asString());
    }
*/

    @Test
    @DisplayName("Single moxtures, chained. (Create Owner, Pet, verify association)")
    void checkCreateOwnerAndPet() 
    {
        // 1. Create the Owner (saves 'ownerId' automatically)
        mx.caller().call("create_owner");

        // 2. Create a Pet for that Owner
        // 2.1. By overriding the name
        String petName = "Rex";
        // Make sure the default moxture var is different:
        String moxtureLocalpetName = mx.vars("create_pet_for_owner")
                                       .read("in_petName").asString();
        assertNotEquals(petName, moxtureLocalpetName);

        mx.caller()
          .with("in_petName", petName)  // Overrides the default var provided in the moxture
          .call("create_pet_for_owner")
          .assertVar("petId"     , id   -> id.isNotNull())
          .assertVar("petName"   , name -> name.isEqualTo(petName))
          .assertVar("petOwnerId", id   -> id.isEqualTo(mx.vars().get("ownerId")));

        // 2.2. By using the fixture default ("Snowy" from the YAML moxture)
        mx.caller()
          .call("create_pet_for_owner")
          .assertVar("petName", name -> name.isEqualTo(mx.vars("create_pet_for_owner").get("in_petName")));
    }


    @Test
    @DisplayName("Read a moxture-local defined var.")
    void checkMoxtureScopedVars() 
    {
        String moxtureLocalpetName = mx.vars("create_pet_for_owner")
                                       .read("in_petName").asString();
        log.debug("in_petName = {}", moxtureLocalpetName);
        assertEquals("Snowy", moxtureLocalpetName);
    }


    @Test
    @DisplayName("Group moxture with moxture-local var, and call override.")
    void checkCreateOwnerAndPet_10() 
    {
        String petName = "Rex";

        // Make sure the default moxture var is different (or this test tests nothing ^^):
        String moxtureLocalpetName = mx.vars("create_pet_for_owner")
                                       .read("in_petName").asString();
        assertNotEquals(petName, moxtureLocalpetName);

        mx.caller()
          .with("in_petName", petName)  // Overrides the default var provided in the moxture
          .call("group_create_owner_and_pet")
          .assertVar("petName", name -> name.isEqualTo(petName));
    }


    @Test
    @DisplayName("Group moxture with moxture-local var, no call override.")
    void checkCreateOwnerAndPet_11() 
    {
        String moxtureLocalPetName = mx.vars("create_pet_for_owner")
                                       .read("in_petName").asString();

        mx.caller()
          .call("group_create_owner_and_pet")
          .assertVar("petName", name -> name.isEqualTo(moxtureLocalPetName));
    }


    @Test
    @DisplayName("Group moxture with group-local var, and call override.")
    void checkCreateOwnerAndPet_12() 
    {
        String petName = "Rex";

        // Make sure the default moxture var is different (or this test tests nothing ^^):
        String groupLocalpetName = mx.vars("group_create_owner_and_pet_with_local_override")
                                       .read("in_petName").asString();
        assertNotEquals(petName, groupLocalpetName);

        mx.caller()
          .with("in_petName", petName)  // Overrides the default var provided in the moxture
          .call("group_create_owner_and_pet_with_local_override")
          .assertVar("petName", name -> name.isEqualTo(petName));
    }


    @Test
    @DisplayName("Group moxture with group-local var, no call override.")
    void checkCreateOwnerAndPet_13() 
    {
        String groupLocalpetName    = mx.vars("group_create_owner_and_pet_with_local_override")
                                        .read("in_petName").asString();
        String moxtureLocalPetName = mx.vars("create_pet_for_owner")
                                       .read("in_petName").asString();

        // Make sure the 2 are different (or this test tests nothing ^^):
        assertNotEquals(groupLocalpetName, moxtureLocalPetName);

        mx.caller()
          .call("group_create_owner_and_pet_with_local_override")
          .assertVar("petName", name -> name.isEqualTo(groupLocalpetName));
    }


}
