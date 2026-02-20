# A First Simple Example

This chapter demonstrates the most common workflow in Moxter: executing a basic HTTP request and capturing data from the response to use later in your test suite.

## The YAML Definition
Place a file named fixtures.yaml in your test resources (e.g., `src/test/resources/moxter/simple/`). This file describes the "what"—the declarative state of your HTTP call.

```yaml
fixtures:
  - name: create_pet
    method: POST
    endpoint: /api/pets
    expectedStatus: 201
    payload:
      firstName: "Thomas "
      lastName: "O'Malley"
      address: "Alleyways and rooftops"
      city: "Paris"
    save:
      petId: $.id    # Captures the 'id' field from the JSON response body
                     # (usint JSonPath syntax) so it can be used either in # another fixture, or inside the JUnit test.
```



## The Java Execution
In your JUnit test, you build the engine and call the fixture by its symbolic name. Moxter handles the MockMvc execution, JSON serialization, and status assertions automatically.

```Java
@SpringBootTest
@AutoConfigureMockMvc
class PetIntegrationTest {

    @Autowired
    private MockMvc mockMvc; // Injected by Spring Test context

    private static Moxter mx;  // Our fixture engine

    @BeforeAll
    static void setup(@Autowired MockMvc mockMvc) {
        // Build the engine for this test class
        mx = Moxter.forTestClass(OwnerIntegrationTest.class)
                .mockMvc(mockMvc)
                .build();
    }

    @Test
    void testShampooOnPet() {
        
        // 1. First we'll need a pet
        // => Execute the fixture defined in YAML
        mx.callFixture("create_pet");

        // 2. Retrieve the captured variable using Moxter's typed accessors
        Long newId = mx.varsGetLong("ownerId");
        
        assertNotNull(newId, "The pet ID should have been captured from the response");

        // Now that we have our pet created, we can perform the actual 
        // logic intended for this test:
        
        // Testing the shampoo:
        ...

    }
}
```



## How it Works
Moxter acts as a coordination layer between your declarative YAML files and the Spring MockMvc framework.

Discovery: The engine finds the fixtures.yaml based on your test's package name.

Resolution: Any placeholders like {{var}} in the YAML are replaced by values currently in the engine's memory.

Assertion: If the server returns anything other than the expectedStatus (201 in this case), the test fails immediately with a descriptive error message showing the response body.

Persistence: The save block extracts the ID from the response and stores it in a shared map, making it available for any subsequent fixture calls or Java assertions.


## Scope
While **Moxter** was originally conceived as a helper for setting up test context (the "Given" part) which can sometimes prove quite tedious, you may have noticed that nothing stops you from using fixture-driven calls to your API to drive the core logic of the test itself, thus allowing you to orchestrate entire integration scenarios (almost) without leaving your YAML.