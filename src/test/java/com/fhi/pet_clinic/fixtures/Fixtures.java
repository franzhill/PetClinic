package com.fhi.pet_clinic.fixtures;

import java.lang.annotation.*;

/**
 * Annotation to be placed on a JUnit5 test class that allows declaring fixtures to be loaded before running the tests.
 *
 * <p>Use this annotation on a test class to specify one or more entites for which to load fixtures before 
 * each test. Fixtures are JSON files placed in the fixtures folder, with each JSON file 
 * containing a list of entities to be deserialized and saved to the database before the test starts. 
 * This is performed before each test.
 * </p>
 * 
 * <p>Fixtures are loaded in the order they are declared, which is important when entity relationships
 * exist. For example, you may need to load Owners before Pets.</p>
 * 
 * <p>By default, fixtures are loaded before each test method. You can override this
 * with {@code loadMode = Fixtures.lifecycle.PER_CLASS} to load once for the test class.</p>
 * 
 * <p>This annotation must be combined with {@code @ExtendWith(FixtureExtension.class)}.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 *    @ExtendWith(FixtureExtension.class)
 *    @Fixtures(value = { Owner.class, Pet.class }, lifecycle = Fixtures.Lifecycle.PER_CLASS)
 *    public class MyIntegrationTest { ... }
 * }</pre>
 *
 * <p>The fixture loader classes typically implement a common interface or convention and use Jackson
 * to read JSON data and persist it with Spring Data JPA.</p>
 */
@Target(ElementType.TYPE)               // = this annotation can be applied to classes only.
@Retention(RetentionPolicy.RUNTIME)     // = this annotation is retained at runtime i.e. present in the bytecode 
                                        //    so JUnit and extensions can access it via introspection.
@Inherited                              // = this annotation is inherited by subclasses, useful when using abstract base test classes.
public @interface Fixtures 
{
    /**
     * Entity classes for which fixtures should be loaded.
     */
    Class<?>[] value();

    /**
     * Controls when the fixtures are loaded: before each test method or once per test class.
     */
    Lifecycle lifecycle() default Lifecycle.PER_METHOD;

    /**
     * Enum to define fixture loading timing.
     */
    enum Lifecycle 
    {
        /**
         * Load fixtures before each test method (default). Ensures test isolation.
         */
        PER_METHOD,

        /**
         * Load fixtures once per test class. Use when tests can share state.
         */
        PER_CLASS
    }
}