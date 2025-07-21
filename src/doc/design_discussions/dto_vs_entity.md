# DTO vs Entity Mapping Strategy – Balancing Boilerplate and Clean Design

True to my principle of trying to limit boilerplate code as much as possible, I’ve explored several approaches to handle request mapping between incoming payloads and our domain model. As always, there’s no perfect solution — only trade-offs.

The fundamental tension is this:

> Most DTOs are nearly identical to their Entity counterparts, so duplicating field definitions and writing mapping logic feels wasteful.  
> But exposing Entities directly is risky, especially in APIs shared with clients or frontends.

<br />

## > Using Entities Directly Instead of DTOs

This means making the JPA entity double as the API payload class.

### >> Techniques
---

- With **Jackson**:
  - Use `@JsonIgnore`, `@JsonProperty(access = WRITE_ONLY)`, etc.
  - Use `@Transient` for fields not persisted but needed at the API level.

- With **GSON**:
  - Use `@Expose` to control which fields participate in (de)serialization.

### ✅ Pros

- **Minimal boilerplate** — no need to write or maintain separate DTOs.
- Especially useful when DTOs and Entities are 90% identical (which is **very often** the case).
- Works well for internal projects or quick-and-dirty endpoints.
- Automatic deserialization with almost no setup.

### ❌ Cons

- **Mixes concerns** — Entities now serve multiple roles (persistence + API contract).
- Difficult to control what is exposed vs writable vs internal.
- Hard to evolve without breaking consumers (since persistence changes may leak into the API).
- **Doesn’t play well with OpenAPI generation** — auto-generated schema will reflect JPA internals.

<br />


## > Using Records and Projections to Reduce DTO Boilerplate

### >> Java Records for DTOs
---

Java records are a concise way to declare immutable data carriers — a perfect fit for simple request/response DTOs.  
However they are also primarily intended for read-only data and aren't ideal when you need mutable objects for writes, updates, or JPA-managed entities.

```java
public record PetCreateDto(
    String name,
    LocalDate birthDate,
    String sex,
    String species,
    Long ownerId,
    String coatColor,
    String eyeColor,
    Double degeneracyScore
) {}
```

#### ✅ Pros

- ✅ Ultra-concise — no getters/setters/constructors/equals/hashCode/`toString` to write.
- ✅ Immutability by default — great for clean functional-style code.
- ✅ Works well with Jackson, OpenAPI generators (as long as compatible).
- ✅ Encourages clear, flat, intention-revealing DTOs.

#### ❌ Cons

- ❌ Only really suited for Read-only
- ❌ No field-level annotations allowed inside the record declaration (e.g. `@NotNull`, `@JsonProperty`, etc.) — you have to annotate the compact constructor or fields externally.
- ❌ Not ideal for very large or deeply nested DTOs.
- ⚠️ Not all code generators support them equally well (check OpenAPI version compatibility).

<br />

> 💡 Use records when DTOs are flat, simple, and immutable — they shine for request payloads and projections.

<br />

### >> Interface-based Projections (Read-Only DTOs)
---

Spring Data JPA supports defining projections as interfaces that only expose selected fields of an entity — **without writing full DTO classes**.

```java
public interface PetSummary {
    String getName();
    String getSpeciesName();
}
```

And in the repository:

```java
List<PetSummary> findByOwnerId(Long ownerId);
```

Spring will automatically:
- Generate a proxy that reads only the needed fields.
- Avoid loading the full entity unless necessary.

#### ✅ Pros

- ✅ No boilerplate — just declare what you need.
- ✅ Extremely useful for API read models (summaries, lists, etc.).
- ✅ Encourages separation of read/write models (CQRS-like).

#### ❌ Cons

- ❌ Read-only — can’t be used for deserialization or mutation.
- ❌ No logic allowed — no methods, just getters.
- ❌ Fragile if your field names change in the entity.
- ⚠️ Not well-suited for deep or nested object graphs.

<br />

> 💡 Ideal for paginated tables, read-only views, summaries, and frontend optimization.

<br />

### >> Summary
---

| Use case | Strategy |
|----------|----------|
| ✅ Clean, flat request DTOs | Use `record` |
| ✅ Projection of specific fields from Entity | Use `interface`-based Spring projections |
| ❌ Mutable request payloads with validation logic | Stick with standard class-based DTOs |

Both techniques can **reduce boilerplate** and **improve clarity**, especially when paired with MapStruct or other structured mapping approaches.



<br />

## > Using Separate DTOs (Data Transfer Objects)

This is the industry-favored approach (warning! this does not mean it's necessarily the best. Remember EJBs?): DTOs represent the API schema, while Entities remain persistence-only.

### ✅ Pros

- **Clean separation of layers** — API contracts are decoupled from internal model.
- **Compatible with OpenAPI generator** — DTOs can be auto-generated from the spec.
- **Reusable in frontends** — same DTO classes can power client-side types (TS/JS).
- Easier to control validation and serialization.

### ❌ Cons

- **Boilerplate mapping logic** — especially when the DTO and Entity have matching fields.
- Extra layer of maintenance when the domain model changes.
- May feel like overkill for small apps or simple forms.


<br />


## > What Needs to Be Done When Using DTOs

We’ll need a **mapping layer** to translate between DTO and Entity.

### >> Mapping Strategies
---

#### >>> MapStruct (Recommended)
---

- Compile-time code generation.
- Clean, fast, and easy to test.
- Supports repository injection via abstract class.
- Works great with Spring.

Example:

```java
@Mapper(componentModel = "spring")
public abstract class PetMapper {
    @Autowired SpeciesRepository speciesRepo;
    @Mapping(target = "species", expression = "java(speciesRepo.findByName(dto.getSpecies()).orElseThrow())")
    public abstract Pet toEntity(PetCreateDto dto);
}
```

#### >>> ModelMapper
---

- Reflection-based.
- Less verbose, but **slower and less safe**.
- Difficult to inject services or repositories.
- Runtime config needed for enrichment logic.

<br />


### >> Case Study: Mapping a String to an Entity (`Species`)
---

#### >>> What We Want to Achieve
---

Rather than forcing API consumers to send opaque database IDs like:

```json
{
  "speciesId": 345
}
```

…we'd like to let them simply send:

```json
{
  "species": "DOG"
}
```

This is:
- More intuitive for clients (who likely don’t care about internal DB IDs)
- Easier to debug and test
- More readable in logs, Postman, and OpenAPI docs
- More stable across environments (IDs can change, names generally don’t)

We want this `species` string (e.g. `"DOG"`) to be resolved internally into a `Species` JPA entity, and injected into the resulting domain object.



#### >>> Strategies to Resolve a String to an Entity
---




##### >>>> Option 1: Enrich in the Service Layer
---

In this approach, a clear distinction is kept between:

- `PetCreateDto`: the OpenAPI-generated DTO received from the client, with simple fields like `String species`, `Long ownerId`.
- `Pet`: the JPA entity model, which expects full references (`Species`, `Owner`).

A mapper (e.g. MapStruct) is used to copy the simple fields from `PetCreateDto` into a partially constructed `Pet` object, and then enrich that object inside the `PetService` before saving it.  
This is where the translation happens:

```java
public Pet createPet(PetCreateDto dto) 
{
    Pet pet = petMapper.toEntity(dto); // Maps simple fields, species still null

    Species species = speciesRepository.findByNameIgnoreCase(dto.getSpecies())
                                       .orElseThrow(
                                        () -> new IllegalArgumentException(
                                             "Unknown  species: " + dto.getSpecies()));
    pet.setSpecies(species);
    return petRepository.save(pet);
}
```

✅ Simple and explicit — each step is clear and under your control.  
✅ No Spring binding magic — avoids confusion caused by @InitBinder, custom deserializers, etc.  
✅ Easy to debug and log — you can log unresolved species names or fallback behavior.  
❌ Slightly more verbose — enrichment code lives in the service layer, and may repeat across similar services if not factored out.  
❌ Split logic — mapping and enrichment are separated, which can feel less cohesive than having it all in one place (e.g. in the mapper).  

> This approach works well when clarity is valued handling a bit of logic in the service is not a problem. It also makes unit testing easier by keeping repository access and enrichment explicit and injectable.

<br />

##### >>>> Option 2: Use `@Mapping(expression = "...")` in MapStruct
---
The enrichment logic is pushed into the mapper itself:

```java
@Mapper(componentModel = "spring")
public abstract class PetMapper 
{
    @Autowired SpeciesRepository speciesRepo;

    @Mapping(target = "species", 
             expression = "java(speciesRepo.findByNameIgnoreCase(dto.getSpecies()).orElseThrow())")
    public abstract Pet toEntity(PetCreateDto dto);
}
```

✅ Keeps service layer ultra-minimal  
✅ All mapping logic lives in one place  
⚠️ Slightly harder to unit test  
⚠️ Can feel "magic" if abused


<br />

##### >>>> Option 3: Custom JSON Deserializer
---

Attach a `@JsonDeserialize(using = ...)` to the `species` field and resolve the entity in the deserializer.

```java
public class Pet
{   ...
    @JsonDeserialize(using = SpeciesDeserializer.class)
    private Species species;
```

❌ Doesn't play well with Spring DI (you don’t get your repository injected)  
❌ Adds complexity to serialization logic  
✅ Works for enum-style value mapping (not full entities)


<br />

#####  >>>> Option 4: Spring `@InitBinder` with `PropertyEditor`
---
A custom editor for `Species` is registered that resolves a string into an entity:

```java
@InitBinder
public void initBinder(WebDataBinder binder)
{
    binder.registerCustomEditor(Species.class, new SpeciesEditor(speciesRepository));
}
```

⚠️ Works only for fields bound via `@RequestParam` or if the DTO directly contains a `Species` object  
❌ Not triggered when DTOs use `String species`  
✅ Clean Spring idiom — but better suited to simpler types or legacy code


<br />

#### >>> Summary
---
Using business-friendly identifiers (like `"DOG"` instead of `345`) improves API usability and readability. For enrichment, we found that **MapStruct with repository injection** offered the best balance of cleanliness, safety, and developer happiness.


<br />


## > Final Approach Chosen for This Project


After evaluating the above, the following seems like the best compromise:

>**OpenAPI-generated DTOs + MapStruct abstract class**

**Why?**

- Keeps DTOs aligned with the OpenAPI contract (automated, client-friendly).
- Avoids writing mapping boilerplate — MapStruct does the heavy lifting.
- Injects repositories directly in the mapper for field enrichment (`Species`, `Owner`, etc.).
- All logic is centralized and testable.
- Maintains clean layering between API and persistence.

```java
@Mapper(componentModel = "spring")
public abstract class PetMapper 
{
    @Autowired SpeciesRepository speciesRepo;
    @Autowired OwnerRepository ownerRepo;

    @Mapping(target = "species", expression = "java(speciesRepo.findByNameIgnoreCase(dto.getSpecies()).orElseThrow())")
    @Mapping(target = "owner", expression = "java(ownerRepo.findById(dto.getOwnerId()).orElseThrow())")
    public abstract Pet toEntity(PetCreateDto dto);
}
```

> This gives us a pragmatic balance between developer productivity and clean architecture.  
> One mapper, no manual setters, zero surprises.
