package com.fhi.pet_clinic.config;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import com.fhi.pet_clinic.tools.ProfilingQueryExecutionListener;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
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
@Profile({ "local"   // we want the dataProxy to run in local so we can have the profiler.
          ,"dev"     // in dev too
          ,"int"     // in int too
          ,"test"    // See javadoc above for whether to activate or not 
                     // We might want it for the test profile also, because we have a test that controls
                     // whether @Transactional rollback still works with this DataSourceProxyConfig.
        })
@Slf4j
public class DataSourceProxyConfig 
{
    @Autowired  // NOSONAR attribute injection. Just let it slide thx.
    private Environment env;

    @PostConstruct
    public void logEffectiveHikariProps() {
        log.debug("Setting provided value forspring.datasource.hikari.auto-commit: {}", env.getProperty("spring.datasource.hikari.auto-commit"));
    }


    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariConfig hikariConfig() 
    {   return new HikariConfig();
    }


    /**
     * Wraps the original application's {@link DataSource} with a proxy
     * that logs all SQL query executions for diagnostics.
     *
     * @param DataSourceProperties the properties for the original datasource.
     *                             We wont' be using a DataSource originalDataSource, see comments inside this method.
     * @return a proxied data source that logs SQL calls
     */
    @Bean
    @Primary
    public DataSource proxiedDataSource(DataSourceProperties dsProps,
                                        HikariConfig hikariConfig,
                                        ProfilingQueryExecutionListener queryExecutionListener) 
    {
        // Original datasource.
        // We won't be doing
        //     HikariDataSource hikari = (HikariDataSource) originalDataSource;  
        // with originalDataSource injected in this method's constructor by Spring,
        // because at that point apparently the injection happens to early in the in the Spring lifecycle
        // and the bean is not yet completely configured.
        // Specifcally, it seems auto-commit is not yet configured.

        hikariConfig.setJdbcUrl (dsProps.getUrl());
        hikariConfig.setUsername(dsProps.getUsername());
        hikariConfig.setPassword(dsProps.getPassword());

        HikariDataSource hikariDs = new HikariDataSource(hikariConfig);

        log.debug("Original Hikari auto-commit: {}"      , hikariDs.isAutoCommit());  // should pick up what's in application.yaml
        log.debug("Original Hikari maximum-pool-size: {}", hikariDs.getMaximumPoolSize());  
        log.debug("Original Hikari minimum-idle: {}"     , hikariDs.getMinimumIdle());
        log.debug("Original Hikari pool-name: {}"        , hikariDs.getPoolName());

        DataSource proxiedDataSource = ProxyDataSourceBuilder
                .create(hikariDs)
                .name("PROXY-DS")  // Used in logs
                .listener(queryExecutionListener)  // Our custom listener
                .multiline()  // Pretty-print multiline SQL logs
                .countQuery() // Appends query count metrics
                .build();

        try (Connection testConn = proxiedDataSource.getConnection()) 
        {   log.debug("proxiedDataSource auto-commit : {}", testConn.getAutoCommit());
        } 
        catch (SQLException e) 
        {   log.warn("Failed to get auot-commit status for proxiedDataSource");
        }

        return proxiedDataSource;
    }





    /**
     * The data source that liquibase will use.
     */
    @Bean(name = "liquibaseDataSource")  // => this particular name make liquibase automatically pick it up
    public DataSource liquibaseDataSource(DataSourceProperties properties) 
    {   return properties.initializeDataSourceBuilder().build();
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
    {   return new ProfilingQueryExecutionListener(enabled, logLinePrefix);
    }


}
