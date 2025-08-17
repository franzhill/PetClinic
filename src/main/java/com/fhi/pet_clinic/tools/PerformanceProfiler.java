package com.fhi.pet_clinic.tools;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * A method-level profiler that logs Hibernate-related activity for service methods.
 *
 * Wraps (through @Aspect) a selction of service methods and logs:
 * - The method's execution time
 * - The number of Hibernate queries executed
 * - The number of entities and collections loaded
 * - Optionally, the distinct SQL queries executed if the count exceeds a threshold
 *
 * This profiler is complementary to {@link ProfilingQueryExecutionListener}, which logs every
 * SQL query individually at the JDBC level. While {@code ProfilingQueryExecutionListener} gives
 * per-query execution time and the exact SQL with parameters, this class provides a higher-level
 * overview of what happened during an entire service method execution.
 *
 * In short:
 * - Use {@code PerformanceProfiler} to detect inefficient service methods, excessive queries, and N+1 issues
 * - Use {@code ProfilingQueryExecutionListener} to trace slow or repeated SQL calls and see where they originated
 *
 * Using both together gives full visibility into both the application-level and database-level behavior.
 */
@Aspect
@Component
//@Profile({"local", "dev"})
@Slf4j
public class PerformanceProfiler 
{
    @Value("${profiling.performance.enabled:false}")
    private boolean profilerEnabled;

    @Value("${profiling.performance.slowCallThreshold:500}")
    private int slowCallThreshold;

    @Value("${profiling.performance.queryCountThreshold:50}")
    private int queryCountThreshold;

    @Value("${profiling.performance.printQueryThreshold:5}")
    private int printQueryThreshold;

    @Value("${profiling.performance.logLinePrefix:PROFILING---}")
    private String logLinePrefix;

    private final SessionFactory sessionFactory;

    /**
     * When logging the joinPoint i.e. the method we're profiling, 
     * also print the arguments it was called with.
     * Hint: don't log them unless needed — some args might be sensitive or huge.
     */
    private static final boolean PRINT_ARGS_FOR_JOIN_POINT = false;


    // Constructor autowiring
    public PerformanceProfiler(EntityManagerFactory entityManagerFactory) 
    {   this.sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
    }

    @PostConstruct
    public void init() 
    {
        if (!sessionFactory.getStatistics().isStatisticsEnabled()) 
        {   log.warn("Hibernate statistics are NOT enabled. Enable them via 'hibernate.generate_statistics: true'.");
        } else 
        {   log.info("Hibernate statistics are enabled for profiling.");
        }
    }


    @Around("execution(* com.fhi.pet_clinic.service..*(..))"  //  all methods in 'service' package and subpackages."
           )
    public Object profile(ProceedingJoinPoint joinPoint) throws Throwable 
    {
        if (!profilerEnabled) 
        {   return joinPoint.proceed();
        }

        Statistics stats = sessionFactory.getStatistics();
        stats.clear();
        long start = System.currentTimeMillis();
        try 
        {   return joinPoint.proceed();
        } 
        finally 
        {   // For the aspected method:
            // Execution time.
            long duration       = System.currentTimeMillis() - start; 

            // Nb of queries hitting the DB.
            long queryCount      = stats.getQueryExecutionCount();

            // How many rows from entity tables Hibernate fetched.
            long entityLoadCount = stats.getEntityLoadCount();

            // Nb of persistent collections (e.g. @OneToMany, @ManyToMany) that Hibernate
            // actually initialized (i.e. fetched from DB) during the profiled call.
            // "Loaded" means Hibernate ran a SQL query to initialize the collection.
            // High collectionLoadCount is a symptom for:
            // - Lazy collection loading triggered in loops
            // - N+1 on @OneToMany. We'd typically have queryExecutionCount = collectionLoadCount + 1
            // - Too many collections loaded
            long collectionLoadCount = stats.getCollectionLoadCount();

            // All the queries that were executed.
            String[] queries = stats.getQueries();


            // Print info on the method (join point) that is being profiled:

            // Instead of printing full object contents (which might be large or sensitive),
            // log only their simple class names, separated by commas.
            // This prevents log spam while still giving useful context during profiling.
            String joinPointArgsPrint = Arrays.stream(joinPoint.getArgs())
                                              .map(arg -> arg == null ? "null" : arg.getClass().getSimpleName())
                                              .collect(Collectors.joining(", "));
            String joinPointPrint = joinPoint.getSignature().toShortString() + 
                                   ( PRINT_ARGS_FOR_JOIN_POINT ? "(" + joinPointArgsPrint + ")"
                                                               : ""
                                   ); 

            // Print the whole profile log:
            log.info("{} [{} ms; {} queries; {} entities, {} collections] for [{}]  ([execution time in ms; nb of DB queries; nb of entities loaded])",
                     logLinePrefix,
                     duration,
                     queryCount,
                     entityLoadCount,
                     collectionLoadCount,
                     joinPointPrint
            );


            if (queryCount > 10) 
            {   log.debug("Top queries: {}", Arrays.stream(queries).limit(2).toList());
            }

            // Print warnings if metrics are specifically bad:
            if (duration > 500) 
            {  log.warn("PROFILING --- SLOW CALL DETECTED: [{}] took {} ms", joinPoint.getSignature().toShortString(), duration);
            }
            if (queryCount > 50) 
            {  log.warn("PROFILING --- HIGH QUERY COUNT DETECTED: [{}] generated {} queries", joinPoint.getSignature().toShortString(), queryCount);
            }

        }
    }
}
