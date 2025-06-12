
# PetClinic Cascade Exercise

PetClinic <- OneToMany -> Customer <- oneToMany -> Pet


### Objective

Fix the `ClinicService#createClinic` method so that a `PetClinic` with its `Customers` and their `Pets` is fully persisted.

### Instructions

- Fix missing relationships (`setClinic`, `setCustomer`, etc.)
- Use appropriate `CascadeType`s on `@OneToMany`
- Make the test in `ClinicServiceTest` pass

### Run

```bash
mvn spring-boot:run
```
