

# PetClinic: my "pet" ;o) project — a Spring, JPA & co technical playground
This project serves as a technical playground where I explore and showcase both common and advanced use cases of Spring Boot, JPA/Hibernate, and related technologies.
It aims to offer a clean, production-ready, and well-documented codebase that demonstrates how these technologies work in practice — from everyday challenges to more complex architectural scenarios.

It also hosts a collection of focused exercises on Spring and Hibernate that I regularly submit to my team of junior developers as part of ongoing skills improvement and knowledge sharing.

<br />

# Philosophy & Guiding Principles

- ⚖️ **Thoughtful trade-offs over dogma** <br />
I strongly believe there is no universal “best solution” — only compromises. Every design decision involves trade-offs, and accepting a certain number of downsides. I don’t believe in the absolutism of best practices. I do try to follow them, as long as they don’t interfere with what I’m striving for. Pragmatism, context, and clarity always are my guiding light.

- 🧠 **Clarity and human language over the obscurity of code alone** <br />
Code, even when well-written, is slower to understand than clear, well-chosen words. Relying on code alone to convey intent can lead to ambiguity and effortful guessing. 
I write as if I'm leaving instructions for a future self with partial amnesia and strive to describe and document it to such avail. Understanding what code does should be a matter of reading plain-English explanations. 

- ✂️ **Just what's necessary over bloated "best-practice" over-engineering** <br />
I favour principles like DRY, YAGNI, and KISS over what best-practise and over-egineering gurus might advocate (EJBs anyone?)
Boilerplate, verobosity, repetition etc. give me the ick.
It must be simple to do simple things.

- 🧅 **Graceful layer complexity** <br />
Complexity should be layered. Each level of abstraction should shield higher levels from irrelevant implementation details. When working at one layer, my cognitive load should remain at that level and I shouldn’t have to dig into lower ones to solve or address concerns that don’t belong there. Simple things should be simple to do. Deeper complexity should only reveal itself gradually, as needed. Clean layering, separation of concerns, and ultimately thinking about the developer experience are what count.

- 📚 **Share the knowledge** <br />
Code should teach as well as work. You never know the level of the person who’ll read, debug, or extend your work — it could be a junior teammate, a future maintainer, or even your future self in a rush. Don't assume they will know the inticacies of the technologies, framworks etc. That’s why I aim to explain things clearly, with a didactic mindset.



<br />
<br />

# Showcased themes
## Done
### 🏗️ Architecture & Project Structure
- Clean layering between **domain**, **service**, and **API** levels
- Use of `@RestControllerAdvice` for global HTTP exception mapping
- Package structure reflects clean boundaries (e.g. `api/exception` vs `service/exception`)
- Alignment with **Clean Architecture** principles

### 🧪 Integration Testing & Fixtures
- Custom fixture framework with support for:
  - Direct DB fixture loading (fast)
  - API-based fixture loading (realistic)
- Use of `@TransactionalFixtureTest` for rollback-based test isolation
- JSON-based fixtures for both setup and expected results
- Meta-annotations and `TestExecutionListener` integration for declarative tests

### 🛢️ Hibernate & JPA Mechanics
- Lazy vs. eager loading exploration
- Cascade, orphan removal, and lifecycle behavior
- Entity modeling for complex domains (e.g. mating logic, fertility window, degeneracy)
- Exposure to query-related problems (e.g. N+1) and optimization paths

### 📐 Domain-Driven Design Concepts
- Rich domain model with **business rule enforcement**
- Custom exceptions like `MatingException` with cause codes
- Clear separation between **use case logic** and **infrastructure concerns**

### 🛠️ Developer Tooling & Diagnostics
- Detailed `logback-spring.xml` with `%M`, `%line`, `%highlight()` for rich logs
- Environment-specific Spring profiles (`dev`, `test`, `prod`)
- Didactic Javadoc to explain rationale and design choices

### 📚 Educational Value
- Expand didactic Javadoc coverage
- Embed rationale directly in code and configuration
- Use as a live teaching tool for junior developers



## To come
### 🧪 Test Improvements & Metrics
- Visual display of test execution flow and timing
- Runtime fixture reporting and diagnostics
- Smarter test assertions (snapshot diffs, field-aware comparison)
- Parallelized test execution

### 🛢️ Deeper Hibernate Topics
- Detached entity handling and merge patterns
- Lazy loading traps and how to resolve them safely
- L2 caching and query cache experimentation

### 📜 API Contracts & Versioning
- OpenAPI YAML generation & validation
- Backward-compatible API design
- Versioning of endpoints and data contracts

### ✅ Validation & Error Reporting
- Full-fledged input validation (Hibernate Validator / Jakarta Bean Validation)
- User-friendly error messages and API responses
- Localization-ready error structures

### 🕵️ Observability & Logging
- Structured logging of domain/business events
- Log filtering based on environment
- (Optional) Integration with monitoring tools (e.g. Prometheus/Grafana)

### Other
- Actuator


<br />
<br />

# Excercises

## Exercise 01: Hibernate bi-directional relationship and cascade 

### Branches
- exercises/01_hibernate_owning_side_cascade/problem
- exercises/01_hibernate_owning_side_cascade/solution_1
- exercises/01_hibernate_owning_side_cascade/solution_2

### Objective

These are the bidirectional relationships that we have: <br />
  PetClinic <- OneToMany -> Owner <- oneToMany -> Pet
  
The objective of the exercise îs to fix the `ClinicService#createClinic` method so that a `PetClinic` with its `Owners` and their `Pets` is fully persisted.

### Instructions

- Fix missing relationships (`setClinic`, `setOwner`, etc.)
- Use appropriate `CascadeType`s on `@OneToMany`, or not.
- Make the test in `ClinicServiceTest` pass

### Run

```bash
mvn clean test
```
