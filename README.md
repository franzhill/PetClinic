
# PetClinic: a collection of exercises around Spring and Hibernate


## Exercise 01: Hibernate bi-directional relationship and cascade 

### Branches
- exercises/01_hibernate_owning_side_cascade/problem
- exercises/01_hibernate_owning_side_cascade/solution_1
- exercises/01_hibernate_owning_side_cascade/solution_2

### Objective

These are the bidirectional relationships that we have: <br />
  PetClinic <- OneToMany -> Customer <- oneToMany -> Pet
  
The objective of the exercise îs to fix the `ClinicService#createClinic` method so that a `PetClinic` with its `Customers` and their `Pets` is fully persisted.

### Instructions

- Fix missing relationships (`setClinic`, `setCustomer`, etc.)
- Use appropriate `CascadeType`s on `@OneToMany`, or not.
- Make the test in `ClinicServiceTest` pass

### Run

```bash
mvn clean test
```
