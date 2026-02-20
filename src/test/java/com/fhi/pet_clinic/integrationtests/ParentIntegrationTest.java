package com.fhi.pet_clinic.integrationtests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhi.libraries.fixture_engine.FixtureEngine;

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
 * Base class for MockMvc integration tests powered by {@link FixtureEngine}.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Boot a Spring test context with MockMvc.</li>
 *   <li>Provide an authenticated {@link Authentication} (per-request) to FixtureEngine.</li>
 *   <li>Load the closest {@code fixtures.yaml} for the concrete test class.</li>
 *   <li>Run "BeforeAll" / "AfterAll" fixture groups automatically.</li>
 *   <li>Expose a protected {@link #fx} engine and helper {@link #getTestAuthentication()}.</li>
 * </ul>
 *
 * <p>How fixture discovery works (by your engine):</p>
 * <pre>
 * classpath:/integrationtests2/fixtures/{package}/{TestClassName}/fixtures.yaml
 * </pre>
 * where {@code {package}} is the subclass' package as folders and {@code {TestClassName}} is the subclass' simple name.
 *
 * <p>Subclasses can:</p>
 * <ul>
 *   <li>Use {@code fx.callFixture("...")}, {@code fx.callFixtureReturnId("...")} etc.</li>
 *   <li>Override {@link #onBeforeEach()} / {@link #onAfterEach()} to run per-test groups if desired.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles({"test"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // Tells JUnit to create one single instance of the test class for the entire test run of that class => allows non-static @BeforeAll/@AfterAll
public abstract class ParentIntegrationTest 
{
   /** Default test user ID used to build the Authentication. */
   protected static final String TEST_USER = "local";

   /** Injected MockMvc to call the real MVC stack (no real HTTP server). */
   @Autowired protected MockMvc mockMvc;

   /** Shared ObjectMapper for reading responses if needed by tests. */
   @Autowired protected ObjectMapper objectMapper;

   /** Engine: loads fixtures.yaml for the concrete subclass and runs calls. */
   protected FixtureEngine fx;

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
      fx = FixtureEngine.forTestClass(getClass())
               .mockMvc(mockMvc)
               .authentication(getTestAuthentication())
               .build();

      // Run "BeforeAll" fixtures group defined in the closest fixtures.yaml (if any).
      fx.callFixture("BeforeAll");
   }


   @AfterAll
   void teardownBase() 
   {  // Run "AfterAll" fixtures group defined in the closest fixtures.yaml (if any).
      fx.callFixture("AfterAll");
   }


   @BeforeEach
   void perTestBase() 
   {  fx.callFixture("BeforeEach");
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
   {  fx.callFixture("AfterEach");
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
