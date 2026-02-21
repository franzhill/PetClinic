# Introduction


**Moxter** (previously **FixtureEngine**) is a lightweight, standalone, configuration-oriented framework that leverages Spring's `MockMvc` to facilitate and rationalize the setting up of JUnit automated tests.

It provides a declarative way to describe test setup steps - sometimes referred to as "fixtures", especially in the Python ecosystem - necessary to set up the context in which the core JUnit test logic should perform, relying on calls to your exposed REST API to do so.

## The Problem: Setup Fatigue
Instead of setting up pre-test contexts "by hand" via scattered `MockMvc` calls and JSON boilerplate throughout the tests themselves, **Moxter** centralizes and configures these steps in YAML files. 
These can then be executed on demand from within the test itself.


## Why use Moxter?
Using **Moxter** provides several key advantages for maintainable test suites:
- **Shorter, Cleaner Tests**: Your JUnit tests focus purely on the core assertions rather than the "plumbing" of setup.
- **Readability**: YAML provides a clean, human-readable format for describing data and API interactions.
- **Maintainability**: When an API endpoint changes, you update the YAML fixture in one place rather than hunting through dozens of Java test files.
- **Reusability**: Fixtures can be shared across multiple test classes or even chained together to form complex scenarios.


<br /><br />


# TL;DR

- A **fixture** is a declarative configuration for an HTTP call executed via `MockMvc`. (the term is popular in the Python ecosystem)
- Fixtures are defined in `fixtures.yaml` files (next to your test class; see path rules below).
- Fixtures are callable from JUnit tests.
- In your JUnit tests, build the engine with:  
  `FixtureEngine.forTestClass(getClass()).mockMvc(mockMvc).authentication(auth).build()`
- Then call fixtures by name with:  
  `fx.callFixture("create_bcs")`, `fx.callFixtureReturnId("create-offer")`, …
- Payloads can be YAML/JSON objects, JSON strings, or `classpath:` includes.
- Responses can save variables via JsonPath:  
  `save: { myId: $.id }` → variables can be reused in other fixtures or retrieved in tests.
- Fixtures can also be grouped and executed together just like a single fixture.
- Auth can be provided to the engine so that it is automatically attached per request and CSRF tokens auto-added on mutating verbs; no need to touch `SecurityContextHolder`.

<br />