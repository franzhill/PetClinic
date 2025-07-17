package com.fhi.pet_clinic.fixtures_fmwk.springfixtureloader.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.transaction.annotation.Transactional;

import com.fhi.pet_clinic.config.SpringTestConfig;
import com.fhi.pet_clinic.fixtures_fmwk.annotation.Fixtures;
import com.fhi.pet_clinic.fixtures_fmwk.springfixtureloader.FixtureTestExecutionListener;


 /**
 * Meta-annotation for Spring Boot integration tests with transactional fixture-based data loading.
 *
 * This annotation:
 * - Boots the full Spring application context once per test class
 * - Ensures transactional rollback after each test method (to isolate test data)
 * - Registers {@link FixtureTestExecutionListener} to load JSON fixtures automatically
 * - Supports {@link Fixtures} lifecycle modes, including {@code PER_CLASS} to load data only once
 *
 */
@Target(ElementType.TYPE)               // = this annotation can be applied to classes only.
@Retention(RetentionPolicy.RUNTIME)     // = this annotation is retained at runtime i.e. present in the bytecode 
                                        //    so JUnit and extensions can access it via introspection.

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

// Activates the Spring 'test' profile.
// This will make Spring Boot automatically pick up:
// - application.yml
// - and override with: application-test.yml
@ActiveProfiles("test")

// Ensures JUnit uses a single instance of the test class => the same instance is reused
// for all test methods, which enables @BeforeAll methods to be non-static (and lets 
// you carry state between tests if needed).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)

// Automatically configures MockMvc for testing HTTP endpoints
@AutoConfigureMockMvc

// Ensures each test method runs in a transaction that is rolled back after the test.
// This guarantees that fixture data does not leak across tests.
@Transactional

// Registers the custom FixtureTestExecutionListener that loads test fixtures 
// before each test method (or once per class, depending on @Fixtures lifecycle).
// mergeMode ensures we don't override Spring Boot's default listeners.
// Even though Spring creates and wires the FixtureTestExecutionListener, JUnit
// doesn’t know it’s there unless we still register it manually as we're doing here.
// Why? Because @TestExecutionListeners looks for the class, not the bean. It doesn’t 
// care if a Spring-managed instance exists — it always instantiates its own unless 
// you override TestContextBootstrapper, which is advanced territory.
@TestExecutionListeners(
    value = FixtureTestExecutionListener.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)

// Imports additional Spring test configuration, including beans required during tests 
// (e.g., fixture loader, test-specific overrides, or mock beans).
// In particular, this includes the GenericFixtureLoader and any related support infrastructure.
@Import(SpringTestConfig.class)

// It is possible to filter by tag when running tests in Maven, Gradle, or your IDE.
// For example, we could chose to run only fast unit tests by chosing only @Tag("smoke")
//
// It is possible to efine multiple tags by stacking multiple @Tag annotations on a class
// or method:
// E.g.:
// @Tag("SpringIntegrationTest")
// @Tag("Smoke")
//
// Usage examples:
// - In Maven: mvn test -Dgroups=SpringIntegrationTest
// - In Gradle: ./gradlew test --tests * --include-tag SpringIntegrationTest
// - In IntelliJ: Edit Run Configuration → Tags → Include/Exclude specific tags
@Tag("SpringIntegrationTest")

public @interface SpringIntegrationTest 
{}