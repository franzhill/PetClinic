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

```java
@SpringFixtureTest
@Fixtures({ Owner.class, Pet.class })
class MyIntegrationTest {
    // Each test runs with fresh data and leaves no residue.
}
```

Alternatively, if you prefer not to use the meta-annotation:

```java
@SpringBootTest
@Transactional
@TestExecutionListeners(
    value = SpringFixtureLoader.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
@Fixtures({ Owner.class, Pet.class })
class MyIntegrationTest { ... }
```

### >> Meta-annotation

A shortcut to encapsulate all the boilerplate exists:

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@Transactional
@TestExecutionListeners(
    value = SpringFixtureLoader.class,
    mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
public @interface SpringFixtureTest {}
```


### >> Loading lifecycle

Optional control is given on when the fixtures are loaded with `Fixtures.Lifecycle` (`PER_METHOD` or `PER_CLASS`)
  The default  is `PER_METHOD`.  
  
Example:

```java
@Fixtures(value = { Owner.class, Pet.class }, lifecycle = Fixtures.Lifecycle.PER_CLASS)
```



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



<br />
<br />


## > 3. Common to both: Fixture Configuration

Fixtures are declared in JSON and stored in:

```
src/test/resources/fixtures/tests/<TestClassName>/<EntityClass>.json
```

For example:

```
src/test/resources/fixtures/tests/MyIntegrationTest/Owner.json
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





