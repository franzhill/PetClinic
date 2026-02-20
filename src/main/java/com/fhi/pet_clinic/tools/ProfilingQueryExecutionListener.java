package com.fhi.pet_clinic.tools;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;

/**
 * SQL query execution listener for the datasource-proxy performance profiler.
 *
 * <p>This listener is registered on the proxied DataSource used for performance profiling
 * allowing for tracing and analysis of SQL usage patterns.
 *
 * <p>It attempts to identify the application-level method that triggered the SQL statement,
 * skipping infrastructure layers (Spring, Hibernate, Quartz, etc).
 * 
 * <p>It logs every SQL statement executed by the application,
 * including its execution time and the Java method (class, line number)
 * that triggered it. This is particularly useful for tracing N+1 queries,
 * diagnosing slow SQL, and debugging unexpected query patterns.
 *
 * <p>It works well in local/dev environments when integrated with a
 * proxied {@code DataSource} via {@link net.ttddyy.dsproxy.support.ProxyDataSourceBuilder}.
 *
 * <p>Note: For performance and log readability, consider adding a threshold
 * to skip logging fast queries, or log only those exceeding a certain duration.
 */
@Slf4j
public class ProfilingQueryExecutionListener implements QueryExecutionListener
{
   /*
    * If enabled, outputs log lines according to logging setup.
    */
   private final boolean enabled;

   /**
    * Prefix this to log lines with this to make searching or reading easier.
    */
   private final String logLinePrefix;

   /**
    * Package prefixes for known infrastructure layers (JDK, Spring, Hibernate, etc.) 
    * that should be skipped when examining the stack to find the meaningful 
    * application-level caller/entry point.
    */
    private static final List<String> IGNORED_PACKAGES = List.of(
        "java.",
        "jakarta.",
        "org.springframework.",
        "org.hibernate.",
        "org.quartz.",
        "com.zaxxer.",
        "net.ttddyy.",
        "ch.qos.logback."
    );

    /**
     * Fully qualified class names within our own codebase that are not considered meaningful 
     * application-level caller/entry points.
     */
    private static final List<String> IGNORED_CLASSES = List.of(
        ProfilingQueryExecutionListener.class.getName()
        // add here if needed
    );


   private static final String APP_PACKAGE_START = "com.fhi.pet_clinic";


    public ProfilingQueryExecutionListener(boolean enabled, String logLinePrefix) 
    {   this.enabled = enabled;
        this.logLinePrefix = logLinePrefix;
    }


   /**
    * Executed after each SQL query execution.
    *
    * @param execInfo      execution metadata (success, time, etc.)
    * @param queryInfoList one or more queries involved in the operation
    */
    @Override
    public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) 
    {   
      if (!enabled) return;

      long elapsedTime = execInfo.getElapsedTime();

      String combinedSql = queryInfoList.stream()
                                        .map(QueryInfo::getQuery)
                                        .collect(Collectors.joining("\n"));

      StackTraceElement caller =  findApplicationCaller()
                                 .orElse(new StackTraceElement("unknown", "unknown", "unknown", -1));

      try 
      {  log.info("{} in [{}:{}:{}], executed SQL request in {} ms: \n{}",
                   logLinePrefix,
                   Class.forName(caller.getClassName()).getSimpleName(),
                   caller.getMethodName(),
                   caller.getLineNumber(),
                   elapsedTime,
                   combinedSql
                 );
      } 
      catch (ClassNotFoundException e) // Could happen if the class is not visible to the current classloader
                                       // e.g. loaded by a different classloader (e.g. in modular environments, 
                                       // servlet containers, or tests using different classloaders), the 
                                       // current thread context may fail to see it,
                                       // or if it's dynamically generated etc.
      {  // Shouldn't happen since we're getting class names from the running stack trace
         // However in practise, it does! => Ignore or else we might end up polluting logs too much.
         log.trace("ClassNotFoundException while trying to print profiling logs: {}", e.getMessage());
      }
    }


   /**
    * Attempts to identify the first meaningful application-level stack frame
    * that triggered the SQL execution, skipping internal proxy and listener code.
    *
    * <p>This is used for logging purposes to trace SQL queries back to the Java class
    * and method that caused them, excluding infrastructure layers, proxy mechanisms
    * and this current class.
    *
    * <b> Note:
    * Some calls go through a stack trace like:
    *   - AuthorizationUtils:findById
    *   -- AuthorizationService.check(HttpServletRequest request, Class<?> entityClass, Long entityId, String fieldName)
    *   --- EventRC.bulk(@RequestBody List<HashMap<String, Object>> commonEvents, HttpServletRequest request)
    *   ---- public void bulk(@RequestBody List<HashMap<String, Object>> commonEvents, HttpServletRequest request)
    *
    *  Returning "AuthorizationUtils:findById" as the application caller might not be very relevant.
    *  We should go up a bit, up to bulk() maybe, but also somehow convey the fact that we're in a check subconcern.
    *
    * @return the first {@link StackTraceElement} matching application code, or a placeholder if none found.
    */
   private Optional<StackTraceElement>  findApplicationCaller() 
   {
      return Arrays.stream(Thread.currentThread().getStackTrace())
                   .filter(element -> {
                                        String className = element.getClassName();
                                        return     className.startsWith(APP_PACKAGE_START)
                                                && !shouldIgnoreClass(className);
                   })
                   .findFirst();
   }


    /**
     * Determines if a class name belongs to an infrastructure package or explicitly ignored class.
     *
     * @param className the fully qualified class name
     * @return true if the class should be excluded from profiling
     */
    private boolean shouldIgnoreClass(String className) 
    {
        return     IGNORED_PACKAGES.stream().anyMatch(className::startsWith)
                || IGNORED_CLASSES.contains(className);
    }


    @Override
    public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) 
    {
        // No action needed before query execution
    }
}