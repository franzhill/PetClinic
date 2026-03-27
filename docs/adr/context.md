

## Context

### Moxter
Moxter is a declarative, YAML-based integration testing engine piggy-backing on MockMvc for Spring Boot. It uses "moxtures" inside in YAML files that describe  HTTP/STOMP requests to the backend API, and assertions on their execution and return. The goal of Moxter is to replace complex Java-based REST Assured tests with human-readable, versionable YAML.


### PetClinic: a Spring, JPA & co technical playground
PetClinic is a project serving as a technical playground where I (the author, aka FHI) explore and showcase both common and advanced use cases of Spring Boot, JPA/Hibernate, and related technologies.
It aims to offer a clean, production-ready, and well-documented codebase that demonstrates how these technologies work in practice — from everyday challenges to more complex architectural scenarios.

As you may notice, PetClinic also provides the "meat" for testing Moxter (by offering a REST API).


