package com.fhi.pet_clinic.moxter_tests.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fhi.pet_clinic.moxter_tests.ParentMoxterTest;

import lombok.extern.slf4j.Slf4j;


/**
 * Run with:
 *   mvn clean test -Dtest=CrudTest
 * 
 * 
 * - A note on my test function names: 
 *   To spare myself quite some amount of hassle I am treating test function
 *   names as just tokens (test_1, test_2, etc.). This eliminates "Refactoring 
 *   Friction" and function names getting gradually longer and unwieldy (don't
 *   even get me started on "you should not have '_' in your function names!" ^^)
 *   Instead I rely on @DisplayName as the single source of truth for test intent.
 *   So how does that play along with Tooling & CI/CD?
 *   - Modern IDEs (IntelliJ/VS Code) prioritize @DisplayName in the test runner UI.
*    - For CI/CD (Jenkins/GitHub Actions/Maven), we should use the Surefire 
 *    'usePhrasedTestCaseMethodName' reporter. This ensures that even if the 
 *     raw Java method is 'test_7', the build logs and XML reports reflect the 
 *     human-readable @DisplayName.
 *   - In raw, unconfigured terminal outputs, we might unfortunately still see 
 *     just the function name.
 */
@Slf4j
class CrudTest extends ParentMoxterTest 
{
    @Test
    @DisplayName("Single moxtures, chained. (Create Owner, Pet, verify association)")
    void test_1() 
    {
        // 1. Create the Owner (saves 'ownerId' automatically)
        mx.caller().call("create_owner");

        // 2. Create a Pet for that Owner using the moxture defaults variables
        mx.caller()
          .call("create_pet_for_owner")
          .assertVar("petName", name -> name.isEqualTo(mx.vars("create_pet_for_owner").get("in_name")));
    }


    @Test
    @DisplayName("Single moxtures, chained. Overriding moxture defaults.")
    void test_10() 
    {
        // 1. Create the Owner (saves 'ownerId' automatically)
        mx.caller().call("create_owner");

        // 2. Create a Pet for that Owner
        //    With overriding vars:
        String petName    = "Rex";
        String petSex     = "FEMALE";
        String petSpecies = "Cat";

        // Make sure our vars are different than the moxture default vars 
        // (or we might be hiding failures ^^):
        String moxtureLocalpetName    = mx.vars("create_pet_for_owner").read("in_name"   ).asString();
        String moxtureLocalpetSex     = mx.vars("create_pet_for_owner").read("in_sex"    ).asString();
        String moxtureLocalpetSpecies = mx.vars("create_pet_for_owner").read("in_species").asString();
        assertNotEquals(petName   , moxtureLocalpetName);
        assertNotEquals(petSex    , moxtureLocalpetSex);
        assertNotEquals(petSpecies, moxtureLocalpetSpecies);


        mx.caller()
          .with("in_name"   , petName)  // Overrides the default var provided in the moxture
          .with("in_sex"    , petSex) 
          .with("in_species", petSpecies) 
          .call("create_pet_for_owner")
          .assertVar("petId"     , x -> x.isNotNull())
          .assertVar("petOwnerId", x -> x.isEqualTo(mx.vars().get("ownerId")))
          .assertVar("petName"   , x -> x.isEqualTo(petName))
          .assertJsonPath("$.species.name", x -> x.isEqualTo(petSpecies));
    }


    @Test
    @DisplayName("Read a moxture-local defined var.")
    void test_2() 
    {
        String moxtureLocalpetName = mx.vars("create_pet_for_owner")
                                       .read("in_name").asString();
        log.debug("in_name = {}", moxtureLocalpetName);
        assertEquals("Snowy", moxtureLocalpetName);
    }


    @Test
    @DisplayName("Group moxture with moxture-local var, and call override.")
    void test_3() 
    {
        String petName = "Rex";

        // Make sure our vars are different than the moxture default vars 
        // (or we might be hiding failures ^^):
        String moxtureLocalpetName = mx.vars("create_pet_for_owner")
                                       .read("in_name").asString();
        assertNotEquals(petName, moxtureLocalpetName);

        mx.caller()
          .with("in_name", petName)  // Overrides the default var provided in the moxture
          .call("group_create_owner_and_pet")
          .assertVar("petName", x -> x.isEqualTo(petName));
    }


    @Test
    @DisplayName("Group moxture with moxture-local var, no call override.")
    void test_4() 
    {
        String moxtureLocalPetName = mx.vars("create_pet_for_owner")
                                       .read("in_name").asString();

        mx.caller()
          .call("group_create_owner_and_pet")
          .assertVar("petName", x -> x.isEqualTo(moxtureLocalPetName));
    }


    @Test
    @DisplayName("Group moxture with group-local var, and call override.")
    void test_5() 
    {
        String petName = "Rex";

        // Make sure our vars are different than the moxture default vars 
        // (or we might be hiding failures ^^):
        String groupLocalpetName = mx.vars("group_create_owner_and_pet_with_local_override")
                                       .read("in_name").asString();
        assertNotEquals(petName, groupLocalpetName);

        mx.caller()
          .with("in_name", petName)  // Overrides the default var provided in the moxture
          .call("group_create_owner_and_pet_with_local_override")
          .assertVar("petName", x -> x.isEqualTo(petName));
    }


    @Test
    @DisplayName("Group moxture with group-local var, no call override.")
    void test_6() 
    {
        String groupLocalpetName   = mx.vars("group_create_owner_and_pet_with_local_override").read("in_name").asString();
        String moxtureLocalPetName = mx.vars("create_pet_for_owner")                          .read("in_name").asString();

        // Make sure our vars are different than the moxture default vars 
        // (or we might be hiding failures ^^):
        assertNotEquals(groupLocalpetName, moxtureLocalPetName);

        mx.caller()
          .call("group_create_owner_and_pet_with_local_override")
          .assertVar("petName", x -> x.isEqualTo(groupLocalpetName));
    }


    @Test
    @DisplayName("Moxture with basedOn and expect.body.match")
    //@SuppressWarnings("")
    void test_7()
    {   subTest_1("create_pet_for_owner_with_expect_body_match");
    }


    @Test
    @DisplayName("Moxture with basedOn and expect.body.assert")
    //@SuppressWarnings("")
    void test_8()
    {   subTest_1("create_pet_for_owner_with_expect_body_assert");
    }


    void subTest_1(String moxtureName) 
    {
        mx.caller().call("create_owner");
        mx.caller()
          .call(moxtureName);

        mx.caller()
          // Override moxture defaults:
          .with("in_name"   , "Random_afFik874987")
          .with("in_sex"    , "MALE" )    // has to be a valid sex
          .with("in_species", "Dog")      // has to be a valid species
          // The 'expect' section verifies the input variables appear in the response:
          .call(moxtureName);

        mx.caller()
          // Override moxture defaults:
          .with("in_name"   , "Random_a892222euaonf")
          .with("in_sex"    , "FEMALE" )  // has to be a valid sex
          .with("in_species", "Cat")      // has to be a valid species
          // The 'expect' section verifies the input variables appear in the response:
          .call(moxtureName);
    }


}
