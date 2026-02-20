package com.fhi.libraries.json_simple_fixtures.junitfixtureloader;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.fhi.libraries.json_simple_fixtures.GenericFixtureLoader;
import com.fhi.libraries.json_simple_fixtures.annotation.Fixtures;

/**
 * JUnit 5 extension that integrates the fixture loading mechanism.
 *
 * This extension loads JSON fixtures automatically before each test (@Test),
 * using the {@link Fixtures} and {@link GenericFixtureLoader}.
 * 
 * It detects the current test class and attempts to load fixture files
 * from predefined classpath locations like `fixtures/tests/<TestClassName>/`.
 *
 * Register this extension with:
 * <pre>
 * {@code @ExtendWith(FixtureExtension.class)}
 * </pre>
 */
public class FixtureExtension implements BeforeEachCallback, BeforeAllCallback
{
    // The FixtureExtension implements both BeforeEachCallback, BeforeAllCallback
    // which means both behaviours are active and both beforeEach and beforeAll
    // below are called before repsectively a test method or a test class is run.
    // We'll activate or deactivate the loading of the fixtures, case per case,
    // inside each of the before* methods, according to the specified Fixtures.Lifecycle.


    // Avoid re-loading fixtures for PER_CLASS tests if test class is reused (e.g. in parallel test runs).
    private static final Set<Class<?>> alreadyLoaded = ConcurrentHashMap.newKeySet();

    @Override
    public void beforeEach(ExtensionContext context) 
    {
        Class<?> testClass = context.getRequiredTestClass();
        Fixtures fixtures = testClass.getAnnotation(Fixtures.class);
        if (fixtures == null) return;

        // If PER_METHOD is requested, then yes, load fixtures in beforeEach
        if (fixtures.lifecycle() == Fixtures.Lifecycle.PER_METHOD) 
        {
            loadFixtures(context, fixtures, testClass);
        }
        // If PER_CLASS is requested, then don't do anything in beforeEach
    }


    @Override
    public void beforeAll(ExtensionContext context) 
    {
        Class<?> testClass = context.getRequiredTestClass();
        Fixtures fixtures = testClass.getAnnotation(Fixtures.class);
        if (fixtures == null) return;

        // If PER_CLASS is requested, then yes, load fixtures in beforeAll
        if (    fixtures.lifecycle() == Fixtures.Lifecycle.PER_CLASS
                && alreadyLoaded.add(testClass)) 
        {   loadFixtures(context, fixtures, testClass);
        }
        // If PER_METHOD is requested, then don't do anything in beforeAll
    }


    private void loadFixtures(ExtensionContext context, Fixtures annotation, Class<?> testClass) 
    {
        // We can't @Autowire the GenericFixtureLoader because FixtureExtension is 
        // not itself managed by Spring. JUnit 5 extensions are created by JUnit, 
        // not by Spring. That means Spring doesn't inject dependencies into them.
        GenericFixtureLoader loader = SpringExtension.getApplicationContext(context)
                                                        .getBean(GenericFixtureLoader.class);

        String testName = testClass.getSimpleName();

        for (Class<?> entityClass : annotation.value()) 
        {   try 
            {   loader.load(entityClass, testName);
            } 
            catch (Exception e) 
            {   throw new RuntimeException("Failed to load fixture for entity: " 
                                        + entityClass.getSimpleName() 
                                        + " in test: " + testName, e);
            }
        }
    }


}
