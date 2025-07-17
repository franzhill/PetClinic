package com.fhi.pet_clinic.fixtures_fmwk.springfixtureloader;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.transaction.annotation.Transactional;

import com.fhi.pet_clinic.fixtures_fmwk.GenericFixtureLoader;
import com.fhi.pet_clinic.fixtures_fmwk.annotation.Fixtures;

import lombok.extern.slf4j.Slf4j;


/**
 * Spring {@link org.springframework.test.context.TestExecutionListener} implementation
 * that loads entity fixtures from JSON files before each test method.
 *
 * <p>This listener integrates with Spring's test lifecycle (unlike JUnit extensions) and
 * is compatible with {@code @Transactional} test methods — which allows automatic rollback
 * at the end of each test.</p>
 *
 * <p>It supports two fixture lifecycles:</p>
 * <ul>
 *   <li>{@code PER_METHOD} (default): fixtures are reloaded before each test method</li>
 *   <li>{@code PER_CLASS}: fixtures are loaded once per test class</li>
 * </ul>
 *
 * <p>This class also includes an optional check for {@code @Transactional}. If the test class
 * is not annotated with {@code @Transactional}, a warning (or exception) is issued — since
 * fixture loading assumes rollback-based test isolation.</p>
 *
 * <p>For more information on how to use this mechanism, refer to readme.md file</p>
 */
@Slf4j
public class FixtureTestExecutionListener extends AbstractTestExecutionListener 
{
    private final Set<Class<?>> alreadyLoaded = ConcurrentHashMap.newKeySet();

    // Optional: fail hard if @Transactional is missing
    private static final boolean failIfNotTransactional = false;

    public FixtureTestExecutionListener()
    {   log.debug("Constructor FixtureTestExecutionListener");
    }

    @Override
    public void beforeTestMethod(TestContext testContext) throws Exception 
    {
        log.debug("");
        Class<?> testClass = testContext.getTestClass();
        Fixtures fixtures = testClass.getAnnotation(Fixtures.class);
        if (fixtures == null) return;

        // Check @Transactional presence
        if (!AnnotatedElementUtils.hasAnnotation(testClass, Transactional.class)) 
        {
            String msg = "@Transactional is missing on test class " + testClass.getSimpleName()
                       + " which uses @Fixtures — this may lead to DB state leaks.";

            if (failIfNotTransactional) 
            {  throw new IllegalStateException(msg + " Either add @Transactional or use @TransactionalFixtureTest.");
            } 
            else 
            {  log.warn(msg);
            }
        }

        Fixtures.Lifecycle lifecycle = fixtures.lifecycle();

        log.debug("lifecycle = {}", lifecycle);
        boolean shouldLoad = switch (lifecycle) 
        {
            case PER_METHOD -> true;
            case PER_CLASS -> alreadyLoaded.add(testClass);
        };
        log.debug("shouldLoad = {}", shouldLoad);

        if (!shouldLoad) return;

        // This works because TestContext gives you the Spring context => we don’t need Spring injection in the 
        // attributes.
        GenericFixtureLoader loader = testContext.getApplicationContext().getBean(GenericFixtureLoader.class);

        String testName = testClass.getSimpleName();
        for (Class<?> entityClass : fixtures.value()) 
        {   log.debug("Loading [entityClass, testName] : [{}, {}]", entityClass, testName);
            loader.load(entityClass, testName);
        }
    }
}
