package com.fhi.pet_clinic.tools;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

/**
 * Spring configuration for stack trace filtering rules.
 * <p>
 * Defines the default packages and classes to ignore when
 * scanning the call stack for application-level callers.
 * This config is injected into {@link StackTraceFilter} beans
 * so the filtering logic is decoupled from the actual rules.
 */
@Configuration
public class StackTraceFilterConfig 
{
   /**
    * Package prefixes of infrastructure libraries and frameworks, to be ignored.
    */
   public static final List<String> IGNORED_PACKAGES = List.of(
      "java.",
      "jakarta.",
      "org.springframework.",
      "org.hibernate.",
      "org.quartz.",
      "com.zaxxer.",
      "net.ttddyy.",
      "ch.qos.logback.",
      "com.airbus.ebcs.tools.profiling"
   );

   /**
    * List of fully qualified class names to ignore when looking for
    * meaningful callers in the stack trace.
    */
   public static final Set<String> IGNORED_CLASSES = Set.of(
      "com.airbus.ebcs.tools.profiling.StackTraceUtils"
   );

   /**
    * Package prefixes of callers that we want to filter for 
    * in the stack trace, and retain.
    */
   public static final List<String> RETAINED_CLASSES = List.of(
      "com.airbus.ebcs.rc", 
      "com.airbus.ebcs");


    @Bean
    public StackTraceFilter stackTraceFilter() 
    { return new StackTraceFilter(IGNORED_PACKAGES, 
                                  IGNORED_CLASSES,
                                  RETAINED_CLASSES);
    }
}
