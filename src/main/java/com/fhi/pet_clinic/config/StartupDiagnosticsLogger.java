package com.fhi.pet_clinic.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;


/**
 * Logs useful diagnostics information about the Spring application context and environment properties
 * during startup. 
 * 
 * <p>Intended to assist developers in verifying that profiles, configuration values,
 * and runtime contexts are being applied as expected.
 *
 * <p>This configuration is conditionally enabled by setting:
 * <pre>
 *   app.startup-diagnostics-logger.enabled=true
 * </pre>
 * in the active application profile (e.g. in `application.yaml` ).</p>
  *
 * <p>It is safe to leave this class in production code — it will only activate if explicitly enabled.</p>
 *
 * @author 
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.startup-diagnostics-logger.enabled", havingValue = "true", matchIfMissing = false)
public class StartupDiagnosticsLogger 
{
   private static final String PREFIX = "[Startup Diagnostics]";

   private final Environment environment;

   // -----------------------------
   // Values to be checked
   // ----------------------------- 

   @Value("${spring.liquibase.contexts:__UNSET__}")
   private String liquibaseContexts;

   @Value("${spring.profiles.active:__UNSET__}")
   private String activeProfiles;



   /**
    * Constructor injection of Spring {@link Environment} to allow flexible property lookups.
      *
      * @param environment the Spring environment abstraction
      */
   public StartupDiagnosticsLogger(Environment environment) 
   {  this.environment = environment;
   }


   /**
    * Logs useful debug information to the application logs during application context startup.
    */
   @PostConstruct
   public void logDebugInfo() 
   {
      log.info("{} Diagnostics mode is ON", PREFIX);

      log.info("{} Active Spring profiles         : {}", PREFIX, Arrays.toString(environment.getActiveProfiles()));
      log.info("{} Liquibase contexts             : {}", PREFIX, liquibaseContexts);

      // Example custom properties
      log.info("{} Property: my.custom.flag        = {}", PREFIX, environment.getProperty("my.custom.flag", "NOT SET"));
      log.info("{} Property: spring.datasource.url = {}", PREFIX, environment.getProperty("spring.datasource.url", "NOT SET"));
   }
}
 