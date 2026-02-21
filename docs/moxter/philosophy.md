# Moxter Philosophy

**Moxter** is a backend automated testing framework built on the belief that for most business applications, **integration testing** (the "Black Box approach") provides a higher return on investment than granular **unit testing**. By leveraging the Spring MockMvc framework, **Moxter** encourages you to treat and test your application as a "Black Box".


## The Core Concept: the Black Box Approach
Instead of testing individual Java methods in isolation with Mockito, **Moxter** encourages you to interact with the system (your backend) through its "natural" interface: the REST API.

- **The "What" over the "How"**: You define the desired state and behavior in YAML, and **Moxter** executes it against the API surface.

- **No Mockito brittle setups**: You don't waste effort defining mock behaviors for internal services the implementation of which might change tomorrow, thus breaking tests.

- **High value business scenarios**: by interacting with the interface just like an end-user would, this approach excels at setting up and testing real-life, complex user-level scenarios, thus providing a level of confidence in the correctness of the business value that isolated unit tests cannot match.


## Integration vs. Unit Testing: what we mean
In the scope of automated backend testing, we adopt the following understanding:

- **Unit Testing (Mockito style)**: Focuses on testing a single class in isolation by mocking all of its dependencies. While fast, these tests are "white-box"—they depend heavily on internal implementation details.

- **Integration Testing (Moxter style)**: Focuses on testing the contract of your REST API, engaging the whole chain of internal components (Controller → Service → Repository → Database) as a single unit. <br />
**It's still just automated "mvn test"**: **Moxter** tests are standard JUnit tests. They run during the standard `mvn test` phase alongside any other unit tests.



## Advantages of the Black Box approach
Testing from the outside offers several natural benefits:

- **Refactoring Resilience**: Since the test only cares about the API contract, you can completely rewrite your internal service layer without touching a single test case.

- **Natural Coverage**: One API call often exercises the Controller, Service, Repository, and Database in a single pass, giving you wide coverage with minimal code.

- **True-to-Life Behavior**: You catch issues that Unit Tests miss, such as JSON serialization errors, 403 Forbidden status on specific roles, or database constraint violations.

- **Human Readable**: YAML fixtures serve as "Living Documentation" of how the API is actually supposed to behave.

- **Spring-Native**: Moxter uses the actual Spring Application Context, meaning your security configurations, filters, and database constraints are actually being exercised.

- **Better Stability**: While changing the API contract *could* require updating the integration tests too, a well-designed API contract is inherently more stable than the internal private methods of a service.





## What about performance?

A common concern is that integration tests are significantly slower than pure unit tests. While it is true that starting a Spring Context takes more time than initializing a Mockito runner, **Moxter** is designed to be highly efficient.

By defining **Moxter** tests as `@SpringBootTest(webEnvironment = WebEnvironment.MOCK)`, paired with an H2 embedded database, you gain the full power of the Spring container without the overhead of starting a real HTTP server (like Tomcat/Netty).

The result is a test suite that:
- Starts in seconds, not minutes.
- Avoids network latency by performing "mock" HTTP calls within the same JVM process.
- Provides a realistic environment that is still fast enough to run as part of a standard CI/CD "Pre-push" check.

**In short**: the extra seconds spent on context startup are reclaimed ten-fold by the time saved not writing and maintaining complex mock setups.



## Final Note: A Balanced Testing Strategy
While Moxter champions the Black Box approach for business logic and API workflows, it is important to recognize that Unit Tests have their own distinct strengths.

### When to use Unit Testing (Mockito)
Unit tests remain the superior choice for:
- **Complex Algorithmic Logic**: If you have a method that calculates complex tax brackets or performs heavy mathematical transformations, a unit test is faster and more precise for testing all edge cases.
- **Utility Classes**: Pure functions (like string manipulators or date formatters) should always be unit tested.
- **Extreme Edge Case Coverage**: When you need to simulate a very specific, hard-to-reach case (failure or not), Mockito is often the best tool for the job.

### Complementary Approaches
In a healthy codebase, these two approaches are complementary, not mutually exclusive:
- **Moxter** provides the **macro-view**: It ensures the "plumbing" of your application is connected and that the user's journey is intact.
- **Unit Tests** provide the **micro-view**: They ensure the individual "gears" are machined to the correct specifications.

### The Moxter Rule of Thumb
Start with API Black Box integration testing with **Moxter** to secure the business value. If you find a specific piece of logic that is too complex to exercise fully via the API, supplement it with a focused Unit Test.