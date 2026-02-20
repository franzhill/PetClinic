# Introduction


**Moxter** (previously **FixtureEngine**) is a lightweight, configuration-oriented, Spring `MockMvc`-based utility designed to facilitate and automate JUnit test set-up.

It provides a declarative way to describe test setup steps, aka "fixtures", necessary to set up the context of a JUnit test before performing the core test logic.

Instead of scattering `MockMvc` calls and JSON boilerplate throughout the tests themselves, FixtureEngine centralizes and configures the set-up steps in YAML files, which can then be executed on demand from within the test itself. This makes tests shorter, more readable, and easier to maintain.

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