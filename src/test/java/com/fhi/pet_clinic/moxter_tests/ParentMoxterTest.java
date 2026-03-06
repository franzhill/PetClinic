package com.fhi.pet_clinic.moxter_tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhi.moxter.Moxter;

import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for MockMvc integration tests powered by {@link Moxter}.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Boot a Spring test context with MockMvc.</li>
 *   <li>Provide an authenticated {@link Authentication} (per-request) to FixtureEngine.</li>
 *   <li>Load the closest {@code fixtures.yaml} for the concrete test class.</li>
 *   <li>Run "BeforeAll" / "AfterAll" fixture groups automatically.</li>
 *   <li>Expose a protected {@link #mx} engine and helper {@link #getTestAuthentication()}.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles({"test"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // Tells JUnit to create one single instance of the test class for the entire test run of that class => allows non-static @BeforeAll/@AfterAll
@Slf4j
public abstract class ParentMoxterTest
{
   /** Default test user ID used to build the Authentication. */
   protected static final String TEST_USER = "local";

   /** Injected MockMvc to call the real MVC stack (no real HTTP server). */
   @Autowired protected MockMvc mockMvc;

   /** Shared ObjectMapper for reading responses if needed by tests. */
   @Autowired protected ObjectMapper objectMapper;

   /** Engine: loads fixtures.yaml for the concrete subclass and runs calls. */
   protected Moxter mx;

   // --------------------------------------------------------------------------------------------
   // Lifecycle
   // --------------------------------------------------------------------------------------------
   // About defining multiple lifecycle functions: here’s what JUnit Jupiter
   // does by default across a class hierarchy:
   //   @BeforeAll: parent first → then child
   //   @BeforeEach: parent first → then child
   //   @AfterEach: child first → then parent
   //   @AfterAll: child first → then parent
   // so if both parent and child define @BeforeEach, both will execute (parent’s first). 
   // Same for @AfterEach (but reverse order).

   @BeforeAll
   void bootBase() 
   {
      // Set security context for authenticated test user.
      // Spring Security clears the SecurityContext between tests so this might
      // have to be reissued in every test.
      SecurityContextHolder.getContext().setAuthentication(getTestAuthentication());

      // Create the fixture engine for this class, providing authentication to
      // use on every fixture call.
      mx = Moxter.forTestClass(getClass())
                        .mockMvc(mockMvc)
                        .authentication(getTestAuthentication())
                        .build();

      // Run "BeforeAll" fixtures group defined in the closest fixtures.yaml (if any).
      mx.caller().call("BeforeAll");
   }


   @AfterAll
   void teardownBase() 
   {  // Run "AfterAll" fixtures group defined in the closest fixtures.yaml (if any).
      mx.caller().call("AfterAll");
   }


   @BeforeEach
   void perTestBase() 
   {  mx.caller().call("BeforeEach");
   }


   /**
    * Tip
    * Spring Security wipes the SecurityContext between tests; if you actually 
    * need a context for each test method, set it in @BeforeEach as well:
    */
   @BeforeEach
   void reauthenticate() {
   SecurityContextHolder.getContext().setAuthentication(getTestAuthentication());
   }


   @AfterEach
   void afterTestBase() 
   {  mx.caller().call("AfterEach");
   }



   // --------------------------------------------------------------------------------------------
   // Authentication
   // --------------------------------------------------------------------------------------------

   /**
    * Builds the {@link Authentication} used by tests (simple local user).
    * Subclasses can override to customize user/authorities.
    */
   protected Authentication getTestAuthentication() {
      return new UsernamePasswordAuthenticationToken(
            TEST_USER,                       // principal as a String
            "N/A",                           // credentials unused
            java.util.List.of(new SimpleGrantedAuthority("ROLE_USER"))
      );
   }
}
