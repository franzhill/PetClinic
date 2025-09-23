# Fixture Loading Mechanism for Integration Tests

The fixture framework I am currently working on aims at providing a structured and flexible 
mechanism for loading test fixtures into the database during (before) integration 
tests.

I started off with one strategy, then tried out another one.
So there are **two distinct possible strategies**.
- SpringFixtureLoader
- JUnitFixtureLoader



<br />
<br />



## > 1. Spring Transactional Fixture Loading (Recommended)

### >> Summary

Fixtures are loaded *inside the Spring context* and *within the test transaction*, so that:

- They are rolled back after each test
- No manual purge is needed
- Test classes remain clean and isolated


### >> How to use

A meta annotation was created to encapsulate other annotations and simplify things:

```java
@MetaSpringBootTestWithJsonSimpleFixtures
@Fixtures({ Owner.class, Pet.class })
class MyIntegrationTest {
    // Each test runs with fresh data and leaves no residue.
}
```

Alternatively, if you prefer not to use the meta-annotation, the important annotations are:

```java
@SpringBootTest
@Transactional
@TestExecutionListeners(
    value = FixtureTestExecutionListener.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
@Fixtures({ Owner.class, Pet.class })
class MyIntegrationTest { ... }
```




### >> Loading lifecycle

Optional control is given on when the fixtures are loaded with `Fixtures.Lifecycle` (`PER_METHOD` or `PER_CLASS`)
  The default  is `PER_METHOD`.  
  
Example:

```java
@Fixtures(value = { Owner.class, Pet.class }, lifecycle = Fixtures.Lifecycle.PER_CLASS)
```


### >> How It Works

- A "fixture loading" execution listener (aka "fixture loader") is registered as a **Spring `TestExecutionListener`**.  
- Spring’s testing framework wraps each test method in a **transaction** when `@Transactional` is present.  
- The fixture loader **hooks into the Spring test lifecycle** (right after the `ApplicationContext` is prepared, before the test method executes).  
- At that point, it:
  1. Opens the test transaction.  
  2. Loads the requested fixture files/entities into the database.  
  3. Hands control back to the test.  
- When the test finishes, Spring **rolls back the transaction automatically** → all inserted fixtures vanish, leaving the DB clean.  

👉 This is why you never need a purge step: Spring guarantees full rollback isolation.

<br />

### >> Caveats with the Spring Transactional Strategy

When the **code under test** starts/participates in its **own transactions**, results may differ from the simple “everything rolls back” mental model. Key cases:

#### 1) Default propagation (`REQUIRED`) — joins the test transaction ✅
- Most `@Transactional` methods use **`Propagation.REQUIRED`** (the default).  
- They **join the test method’s transaction**, so **all writes are rolled back** at the end of the test.  
- **Implication:** You cannot observe a real commit inside the same test transaction. If you need to assert post‑commit effects (e.g., visibility to another thread/connection), you must end the test transaction first (see below).

#### 2) `REQUIRES_NEW` or manual `TransactionTemplate` — commits despite outer rollback ⚠️
- Methods using **`Propagation.REQUIRES_NEW`** or an explicit **`TransactionTemplate`** start a **separate transaction** that **commits independently**.  
- Even if the **test method** is rolled back, the **inner `REQUIRES_NEW` work may persist** → **dirty DB after test**.
- **Mitigations (pick what fits best):**
  - **Prefer REQUIRED in tests:** Provide a test‑only bean/config that downgrades `REQUIRES_NEW` to `REQUIRED` for the code path under test (profile-based or conditional AOP).  
  - **Explicit cleanup:** Use a targeted cleanup in `@AfterEach` (truncate affected tables) or `@Sql(executionPhase = AFTER_TEST_METHOD, scripts = "...")`.  
  - **Transactional choreography with `TestTransaction`:**
    ```java
    // End the test transaction before invoking the code that must really commit:
    if (TestTransaction.isActive()) {
        // We typically want isolation; rollback current fixtures to avoid cross-talk
        TestTransaction.flagForRollback();
        TestTransaction.end();
    }

    // Now invoke service method that uses REQUIRES_NEW or real commits
    service.performCriticalCommit();

    // Optionally start a fresh test transaction for assertions/cleanup
    TestTransaction.start();
    // assert DB state, then rely on rollback at test end
    ```
  - **Fallback:** For a small subset of scenarios, use the **JUnit (non-transactional)** strategy + explicit purge to mirror production commit behavior.

#### 3) `NESTED` propagation — savepoints (if supported) ✅
- With **`Propagation.NESTED`**, Spring uses **savepoints** (driver dependent).  
- An **outer rollback rolls back nested work as well** → typically safe in tests.

#### 4) Async / different threads — no participation in test TX ⚠️
- Work run via `@Async`, scheduler threads, or separate executors **does not join** the test transaction by default.  
- Such work may **commit independently** and persist. Consider disabling async in tests, using synchronous executors, or providing explicit cleanup.

#### 5) Read-only transactions 🛈
- If the test (or code under test) uses `@Transactional(readOnly = true)`, some providers may **optimize away writes** or throw exceptions. Ensure write paths are not annotated read-only in tests that insert fixtures.

**Rule of thumb:**  
- If everything uses **`REQUIRED`**, the Spring strategy provides perfect isolation.  
- If any path uses **`REQUIRES_NEW` / async / manual transactions**, add one of the mitigations above to avoid residue or false assertions.





<br />
<br />


## > 2. JUnit Fixture Loading (first strategy I developped)

### >> Summary

Fixtures are loaded using a JUnit 5 extension that hooks into `@BeforeEach` or `@BeforeAll`.
This is the first strategy that I developed, however it does not natively support "purging"
the DB after each test. This can be done, but I haven't got round to it, since the Spring 
transactional strategy seemed better.

### >> How to use

```java
@ExtendWith(FixtureExtension.class)
@Fixtures(value = { Owner.class, Pet.class }, lifecycle = Fixtures.Lifecycle.PER_METHOD)
class MyTest {
    // Fixtures are loaded outside of Spring’s transaction mechanism
}
```

### >> Loading lifecycle

Optional control is given on when the fixtures are loaded with `Fixtures.Lifecycle` (`PER_METHOD` or `PER_CLASS`)
  The default  is `PER_METHOD`.

### >> Purging

Since there's no rollback or DB purging, cleanup must be managed "by hand" if needed

### >> How it works
- The `FixtureExtension` is a **JUnit 5 extension**.  
- It hooks into the **JUnit lifecycle callbacks**:  
  - `beforeEach` → loads fixtures before each test method.  
  - `beforeAll` → loads fixtures once per test class.  
- No Spring transaction is involved, so the data is **committed directly to the database**.  
- That means:
  - Data will persist across tests unless you clean it manually.  
  - You can still control timing (`PER_METHOD` vs `PER_CLASS`), but not rollback.  

👉 This is why the Spring version is preferred: it leverages the built-in transaction management that Spring already provides.


<br />
<br />


## > 3. Common to both: Fixture Configuration

Fixtures are declared in JSON and stored in:

```
src/test/resources/json_simple_fixtures/tests/<TestClassName>/<EntityClass>.json
```

For example:

```
src/test/resources/json_simple_fixtures/tests/MyIntegrationTest/Owner.json
```

Fixtures must be listed in the correct order if there are relationships:

```java
@Fixtures({ Owner.class, Pet.class })
```



<br />
<br />


## > 4. Comparison between the two
  
<br />

| Feature                          | SpringFixtureLoader         | JUnitFixtureLoader          |
|----------------------------------|------------------------------|------------------------------|
| Managed by Spring                | Yes                          | No                           |
| Transaction rollback             | Yes                          | No                           |
| Automatic fixture cleanup        | Yes                          | No                           |
| Requires `@ExtendWith`           | No                           | Yes                          |
| Recommended for most tests       | Yes                          | No (use only if needed)      |







---

### >> JUnit Fixture Loading (Extension-based)
---
- The `FixtureExtension` is a **JUnit 5 extension**.  
- It hooks into the **JUnit lifecycle callbacks**:  
  - `beforeEach` → loads fixtures before each test method.  
  - `beforeAll` → loads fixtures once per test class.  
- No Spring transaction is involved, so the data is **committed directly to the database**.  
- That means:
  - Data will persist across tests unless you clean it manually.  
  - You can still control timing (`PER_METHOD` vs `PER_CLASS`), but not rollback.  

👉 This is why the Spring version is preferred: it leverages the built-in transaction management that Spring already provides.

---