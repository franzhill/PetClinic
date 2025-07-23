package com.fhi.pet_clinic.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import com.fhi.pet_clinic.tools.ProfilingQueryExecutionListener;

import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;

/**
 * Configuration class that wraps (and replaces) the application's real {@link DataSource}
 * with a proxy that intercepts and logs SQL query executions for diagnostics and performance profiling.
 *
 * <p>Should never be enabled in production!
 *
 * <p>This proxy  wraps the real {@code DataSource} and transparently intercepts all JDBC activity.
 * Using {@link ProfilingQueryExecutionListener}, it allows us to systematically log:
 * - The raw SQL statement(s)
 * - The execution time in milliseconds
 * - The Java class and method that triggered the query
 * 
 * <p>Note: Liquibase runs at startup and depends on a {@link DataSource}, 
 * however our proxy also depends on Liquibase in some situations (?)
 * Anyway, Spring conmplains about circular dependency if it uses the proxied datasource.
 * To avoid this, we define:
 * - a clean `originalDataSource` used by the proxy;
 * - a dedicated `liquibaseDataSource` used only by Liquibase;
 * - a proxied `DataSource` marked as `@Primary` for general application use.
 */
@Configuration
//@Profile({"local", "dev"})
public class DataSourceProxyConfig 
{
    /**
     * Wraps the original application's {@link DataSource} with a proxy
     * that logs all SQL query executions for diagnostics.
     *
     * @param originalDataSource the real data source provided by Spring Boot
     * @return a proxied data source that logs SQL calls
     */
    @Bean
    @Primary
    public DataSource proxiedDataSource
         (@Qualifier("originalDataSource") DataSource originalDataSource,
          ProfilingQueryExecutionListener queryExecutionListener
         ) 
    {
        return ProxyDataSourceBuilder
                .create(originalDataSource)
                .name("PROXY-DS")  // Used in logs
                .listener(queryExecutionListener)  // Our custom listener
                .multiline()  // Pretty-print multiline SQL logs
                .countQuery() // Appends query count metrics
                .build();
    }

    /**
     * The real original data source.
     */
    @Bean(name = "originalDataSource")
    public DataSource originalDataSource(DataSourceProperties properties) 
    {   return properties.initializeDataSourceBuilder().build();
    }

    /**
     * The data source that liquibase will use.
     */
    @Bean(name = "liquibaseDataSource")  // => this particular name makes liquibase automatically pick up THIS datasource
    @ConfigurationProperties("spring.datasource")
    public DataSource liquibaseDataSource() {
        return DataSourceBuilder.create().build();
    }


    /**
     * Creates the custom SQL profiling listener to be used in our proxied datasource.
     *
     * @param enabled whether SQL profiling should be active
     * @return a listener that logs SQL query executions if enabled
     */
    @Bean
    public ProfilingQueryExecutionListener queryExecutionListener
          (@Value("${profiling.sql.enabled:false}")              boolean enabled,
           @Value("${profiling.sql.logLinePrefix:PROFILING---}") String logLinePrefix
          ) 
    {
        return new ProfilingQueryExecutionListener(enabled, logLinePrefix);
    }


}

