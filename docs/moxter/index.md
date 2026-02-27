# Introduction

**Moxter** (previously **FixtureEngine**) is a lightweight, standalone, configuration-oriented framework designed to help set up, shape, and organize API-level test scenarios.

Leveraging the `MockMvc` library, it plugs into the `JUnit` test framework to offer developers the ability to easily write and maintain high-level, efficient integration-style test scenarios run during the test phase of the build.

By bringing the ease of declarative API interaction into the heart of your Java codebase, **Moxter** is the missing link between pure isolated unit testing and external API testing with tools like Postman.

<br />

## The philosophy

**Moxter** is built on a "Black Box" testing philosophy. Rather than testing individual Java methods in isolation (standard unit tests), it encourages testing through the application's "natural" interface: the REST API (integration-style testing).

Such a testing approach is achieved by leveraging the @SpringBootTest mechanism, allowing JUnit tests to be performed against a full Spring Application Context without the overhead of spinning up a real HTTP server. This makes them a powerful, yet still efficient, alternative to pure isolated unit tests all within the standard `mvn test` phase.

This is where **Moxter** comes in: by providing the means to easily define the building bricks (so-called "moxtures"), it allows developers to painlessly design and orchestrate larger, more meaningful test scenarios.

<br />

## Why use Moxter?
- **The Sweet Spot**: sitting in between isolated unit tests and external API testing tools, **Moxter** allows you to test real-world scenarios during the standard mvn test phase.
- **Shorter, Cleaner, Meaningful Tests**: **Moxter** will hide all the boilerplate involved in performing MockMVC calls to your application API, letting Your JUnit tests focus purely on the cinematics of the test scenarios and still exerting standard assertions at will.
- **Readability**: "Moxtures" (MockMvc calls) are configured in YAML files thus providing a clean, human-readable and reusable brick.
- **Maintainability**: When an API endpoint changes, you update the YAML "moxture" in one place rather than hunting through dozens of Java test files.
- **Reusability**: "Moxtures" can be shared across multiple test classes 
- **Buildability**: "Moxtures" serve as the fundamental building bricks for advanced test scenarios. You can group multiple moxtures to define new, higher-level moxtures which function just like a simple moxture. Moxtures can be chained together, with the output of one 'fed' into the input of the next one.


<br /><br />


# TL;DR

- A "moxture" (previously "fixture") is a declarative configuration for an HTTP call executed via `MockMvc`. (the term "fixture" is popular in the Python ecosystem)
- Moxtures are defined in `moxtures.yaml` files (next to your test class; see path rules below).
- Moxtures are callable directly from within JUnit tests.
- In your JUnit tests, build the engine with:  
  `Moxter.mockMvc(mockMvc).authentication(auth).build()`
- Then call moxtures by name with:  
  `mx.callMoxture("create_bcs")`, `mx.callMoxtureReturnId("create-offer")`, …
- Payloads can be YAML/JSON objects, JSON strings, or `classpath:` includes.
- Responses can save variables via JsonPath:  
  `save: { myId: $.id }` → variables can be reused in other moxtures or retrieved in tests.
- Moxtures can also be grouped/chained and executed together just like a single moxture, with the ouput of one being fed into the input of the next.
- Auth can be provided to the engine so that it is automatically attached per request and CSRF tokens auto-added on mutating verbs; no need to touch `SecurityContextHolder`.

<br />