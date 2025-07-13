

# PetClinic: my "pet" ;o) project — a technical playground for Spring & JPA
This project serves as a technical playground where I explore both common and advanced use cases of Spring Boot, JPA/Hibernate, and related technologies.
It aims to offer a clean, production-ready, and well-documented codebase that demonstrates how these technologies work in practice — from everyday challenges to more complex architectural scenarios.

It also hosts a collection of focused exercises on Spring and Hibernate that I regularly submit to my team of junior developers as part of ongoing skills improvement and knowledge sharing.



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
