# A First Simple Example

This chapter demonstrates the most common workflow in Moxter: executing a basic HTTP request and capturing data from the response to use later in your test suite.

<br /> 

## The YAML Definition
Place a file named  `fixtures.yaml` in your test resources (e.g., `src/test/resources/moxter/simple/`). This file describes the "what"—the declarative state of your HTTP call.

```yaml
fixtures:            # aka 'moxtures'
  - name: create_pet
    method: POST
    endpoint: /api/pets
    expectedStatus: 201   # make call fail if return status is different
    payload:
      firstName: "Thomas "
      lastName: "O'Malley"
      address: "Alleyways and rooftops"
      city: "Paris"
    save:
      petId: $.id    # Capture the 'id' field from the JSON response body
                     # (using JSonPath syntax) so it can be used either in 
                     # another fixture, or inside the JUnit test.


  - name: apply_approved_shampoo
    method: POST
    endpoint: /api/pet/{{petId}}/shampoo  # We're re-using the petId from the 
                                          # fixture above. 
    expectedStatus: 201
    payload:
      type: "herbal type 3"
      strength: "mild"
      duration: "5 min"
      rinse: "warm"
    save:
      petVitals: $.vitals    # Info on how the pet responds to the shampoo


  # Orchestrating a scenario by grouping fixtures
  - name: all_in_one_shampoo
    fixtures:      # Calling this fixture will result in the chained execution of 
                   # the following fixtures, in the given order:
       - create_pet
       - apply_herbal_shampoo
       # add any other desired fixture


     

```

<br /> 

## The Java JUnit test
In your JUnit test, you build the engine and call the fixture by its symbolic name. Moxter handles the MockMvc execution, JSON serialization, and status assertions automatically.

```Java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class PetIntegrationTest {

    @Autowired
    private MockMvc mockMvc; // Injected by the Spring Test context

    private static Moxter mx;  // Our fixture engine

    @BeforeAll
    static void setup(@Autowired MockMvc mockMvc) {
        // Build the engine for this test class
        mx = Moxter.forTestClass(PetIntegrationTest.class)  
                    // (this will let Moxter know which fixture file(s) to load)
                   .mockMvc(mockMvc)
                   .build();
    }

    @Test
    @DisplayName("Make sure shampoo that says it's approved, does not irritate pet's skin")
    void testShampooOnPet() {
        
        // 1. First we'll need a pet => execute the dedicated fixture defined in the fixture file: 
        mx.callFixture("create_pet");

        // 2. Optional: Access captured variables directly:
        Long newId = mx.varsGetLong("ownerId");
        assertNotNull(newId, "The pet ID should have been captured from the response");

        // 3. Then we'll apply the shampoo on the created pet.
        //    Thanks to chaining, the pet id is automatically passed to 
        //    the 'shampoo' fixture.
        mx.callFixture("apply_approved_shampoo");

        // 4. Retrieve the return captured by Moxter:
        Map<String, Object> vitals = mx.varsGet("petVitals", Map.class)

        // 5. Perform standard JUnit/AssertJ assertions on the pet's health:
        assert(vitals.get("temperature")).inBetween("35 °C").and("40 °C");
        assertThat(vitals.get("heartRate")).inBetween("50").and("150");
        assert(vitals.get("fur")) ... ;
        ...

    }
}
```
 <br />


## How it Works
Moxter acts as a coordination layer between your declarative YAML files and the Spring MockMvc framework.

- **Discovery**: The engine finds the fixtures.yaml based on your test's package name.
- **Resolution**: Any placeholders like {{var}} in the YAML are replaced by values currently in the engine's memory.
- **Assertion**: If the server returns anything other than the expectedStatus (201 in this case), the test fails immediately with a descriptive error message showing the response body.
- **Persistence**: The save block extracts the ID from the response and stores it in the engine's shared memory, making it available for any subsequent fixture calls or Java assertions.

