
# SQL Profiling and Performance Diagnostics

This project includes two complementary profiling mechanisms that enhance visibility into SQL query behavior and ORM performance:

1. **Performance Profiler (Hibernate Statistics)** – "descending": trace how many requests domain level methods have generated.
2. **JDBC SQL Proxy Logging** – trace what domain level method generated individual SQL queries

These tools can prove highly helpful during development and diagnostics to help identify performance bottlenecks and detect inefficient database access patterns.  

Both mechanisms are opt-in and non-invasive.

**Why is this useful?**  
>Hibernate abstracts away SQL – which is great, until performance issues arise.
But once they do, just enabling `org.hibernate.SQL` logs shows what SQL is executed, not who triggered it.  
These tools make it possible to **link domain methods to actual SQL queries played against the DB.**
Understanding **who triggers which SQL**, and **how often**, comes in as essential for:
> - Detecting **N+1 query problems**
> - Profiling **slow queries**
> - Understanding **ORM caching behavior**
> - Tracing code paths that result in SQL calls

<br />

## > 1. Performance Profiler (Hibernate statistics)

### >> Strategy
---
Collect and log **high-level Hibernate metrics** like:

- Number of SQL queries executed per transaction
- Second-level cache hits/misses
- Entity load/fetch count

This gives us a **global view** of ORM activity.
<br />

### >> How it works
---
A Spring `@Aspect` is defined and wraps method execution (usually on service classes) to capture Hibernate metrics before and after method calls.

It uses:
- Hibernate `SessionFactory.getStatistics()`
- Java reflection to log method/class
- Execution time and query count before/after method call

The Aspect logs these different statistics, providing **method-level performance snapshots.**

### >> Example output
---
```
PROFILING (Hibernate stats)--- Method CustomerQueryServiceImpl.findAllAccountByBu took 152 ms, triggered 8 SQL queries, 7 entities loaded, 5 collections loaded
```

### >> Enabling and disabling
---
In app config file (application.yaml e.g.):
```yaml
profiling:
  performance:
    enabled: true
    logLinePrefix: "PROFILING (Hibernate stats)---"
```

### >> Strengths/Limitations
---
✅ Shows cumulative ORM cost of high-level methods  
✅ Exposes cache efficiency  
✅ Helps detect query spam  
❌ Doesn't show actual SQL  
❌ No per-query granularity  

<br />

## > 2. JDBC SQL Profiling via `datasource-proxy`

### >> Strategy
---
Intercept **every actual SQL statement** executed at JDBC level, and log:

- The **SQL**
- The **execution time (ms)**
- The **Java class, method, and line number** that triggered it

This is **code-aware** profiling: it bridges the gap between SQL and your business logic.

<br />

### >> How it works
---
See DataSourceProxyConfig.java.  

- A proxy wraps the `DataSource` using [datasource-proxy] (https://github.com/ttddyy/datasource-proxy)
- The proxy is registered as the primary `DataSource` (via `@Primary`)
- A custom `QueryExecutionListener` logs details after each SQL execution
- To avoid a circular dependency (Liquibase needs a DataSource early), an additional non-proxied `liquibaseDataSource` is defined


### >> Required Maven dependency
---
```xml
<dependency>
    <groupId>net.ttddyy</groupId>
    <artifactId>datasource-proxy</artifactId>
    <version>1.9</version>
</dependency>
```


### >> Output example
---

```
PROFILING (dataproxy)--- SQL executed (11 ms) from [CustomerQueryServiceImpl:findAllAccountByBu:70]
SELECT * FROM customer WHERE region = 'EU'
```


### >> Enabling and disabling
---
In app config file (application.yaml e.g.):
```yaml
profiling:
  sql:
    enabled: true
    logLinePrefix: "PROFILING (dataproxy)---"
```

### >> Strengths/Limitations
--- 
✅ Logs every SQL  
✅ Associates queries to the triggering code  
✅ Measures latency per query  
✅ Works across Hibernate, JPA, JdbcTemplate, etc.  
❌ Cannot introspect ORM-level events (cache, flush, etc.)  
❌ Log volume can be high  

<br />


## > 3. Summary Comparison

| Feature                                | Performance Profiler (Hibernate statistics)  | SQL Proxy (`datasource-proxy`)     |
|----------------------------------------|----------------------------------------------|------------------------------------|
| SQL visibility                         | ❌ No                                        | ✅ Yes                              |
| Execution timing                       | ❌ No                                        | ✅ Yes                              |
| Shows triggering class/method          | ❌ No                                        | ✅ Yes                              |
| ORM cache, flush, lazy loading info    | ✅ Yes                                       | ❌ No                               |
| Tracks total query counts              | ✅ Yes                                       | ❌ Not directly                 |
| Easily filtered in logs                | ✅ Yes (log prefix customizable)             | ✅ Yes (log prefix customizable)        |


<br />


## > 4. Final thoughts

These two tools operate at different layers but together give a **complete view** of:

- What SQL was executed  
- Where in the code it was triggered  
- How long it took  
- What Hibernate was doing under the hood  

Use them during feature development, refactoring, or performance tuning.
