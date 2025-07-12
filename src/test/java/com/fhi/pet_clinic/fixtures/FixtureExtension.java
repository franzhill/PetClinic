package com.fhi.pet_clinic.fixtures;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

public class FixtureExtension implements BeforeEachCallback
{
    @Override
    public void beforeEach(ExtensionContext context) 
    {
        Class<?> testClass = context.getRequiredTestClass();
        Fixtures annotation = testClass.getAnnotation(Fixtures.class);
        if (annotation == null) return;

        GenericFixtureLoader loader = SpringExtension
                .getApplicationContext(context)
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
