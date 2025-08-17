
# 🧪 Spring Boot Integration Tests & Database Lifecycle

This document explains how Spring Boot manages the database lifecycle during integration tests — specifically **when the database is created**, **when it's dropped**, and **how isolation is achieved**.

<br />


## > 🛢️ Database Creation and Dropping

### >> When is the database created?
---

When using an **in-memory database** like H2 **and** `spring.jpa.hibernate.ddl-auto=create-drop`:

- ✅ The database schema is created **once**, when the **application context is first started**.
- This usually happens **once per test class**, if you're using `@SpringBootTest`.
- The same context (and DB schema) is reused across test methods in the same class.
- ❌ The database is **not recreated before each test method**.

### >> When is the database dropped?
---

- With `ddl-auto=create-drop`, the schema is dropped automatically **when the Spring context shuts down**, i.e.:
  - After the test class finishes (if context was specific to that class)
  - Or at the end of the full test suite (if context was reused)


<br />
<br />

## > ♻️ Transactional Rollback and Isolation

### >> What does `@Transactional` do?
---

When a test class or test method is annotated with `@Transactional`:

- Each test method runs in its own transaction
- After the test method completes, the transaction is **rolled back**
- This leaves the database in a **clean state** between tests — **without recreating the schema**

### >> Example
---

```java
@SpringBootTest
@Transactional
class MyIntegrationTest {

    @Autowired
    UserRepository userRepository;

    @Test
    void testCreateUser() {
        userRepository.save(new User("Alice"));
        // Rolled back after the test method finishes
    }
}
```

<br />
<br />

## > 🔎 Controlling DB and Context Scope

| Situation                                       | Context Reused? | DB Recreated? | Notes                                   |
|------------------------------------------------|------------------|----------------|-----------------------------------------|
| Multiple tests in same class                   | ✅ Yes           | ❌ No          | Same context, same schema               |
| Multiple test classes (default)                | ✅ Yes           | ❌ No          | Spring optimizes by reusing context     |
| With `@DirtiesContext`                         | ❌ No            | ✅ Yes         | Forces full context + DB reset          |
| With `@Transactional`                          | ✅ Yes           | ❌ No          | Rollback used instead of full reset     |

<br />
<br />

## > 👍 Best Practices for Integration Tests

✅ Use `@Transactional` to ensure clean state without heavy DB recreation  
✅ Use `ddl-auto=create-drop` for in-memory DBs (e.g. H2) to keep schema fresh  
✅ Use `@DirtiesContext` **only when you need full context isolation**  
✅ Use fixture loaders (e.g. JSON or SQL) to control test data explicitly  
⚠️ Avoid `ddl-auto=update` in tests — unpredictable and risky  
⚠️ Avoid mixing stateful and stateless test strategies in the same class  

<br />
<br />

## > ⚙️ Common Configuration Example


```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: create-drop
  sql:
    init:
      mode: embedded
```

<br />
<br />

## 📌 Summary

- Spring Boot creates the DB **once per context**, usually once per test class.
- Schema is created **at context startup**, dropped **at context shutdown**.
- Use `@Transactional` for **method-level isolation**.
- Use `@DirtiesContext` if you need **class-level isolation with fresh schema**.
