package com.fhi.pet_clinic.config;

import org.springframework.boot.test.context.TestConfiguration;
//#import org.springframework.context.annotation.Bean;
//#import com.fhi.pet_clinic.fixtures.springfixtureloader.FixtureTestExecutionListener;



// Nota: Spring only loads @TestConfiguration classes if you explicitly tell it to 
// — usually via @Import(...), @ComponentScan, or @SpringBootTest(classes = ...).
// Unlike @Configuration, which can be picked up by component scanning in production 
// (aka non test) contexts, the annotation @TestConfiguration is deliberately excluded
// from component scanning. This is by design to prevent it from leaking into the main
// application context.
@TestConfiguration
public class SpringTestConfig 
{
    // Add test-only beans here if needed

//#  We didn't need that in the end
//#    @Bean
//#    public FixtureTestExecutionListener fixtureTestExecutionListener() 
//#    {   return new FixtureTestExecutionListener();
//#    }
}