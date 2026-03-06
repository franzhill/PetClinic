---
### no fixture file found should generate warningn, not error

[INFO]
[ERROR] Errors:
[ERROR]   MyMoxterTest>ParentMoxterTest.bootBase:84 ▒ IllegalState [FixtureEngine] No fixtures file found for com.fhi.pet_clinic.moxter_tests.MyMoxterTest
Expected at: classpath:/fixtures/com/fhi/pet_clinic/moxter_tests/MyMoxterTest/fixtures.yaml
Hint: place the file under src/test/resources/fixtures/com/fhi/pet_clinic/moxter_tests/MyMoxterTest/fixtures.yaml
[INFO]
[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0





---
# To fix

3. Shared Mutable State (The Payload Trap)
The Brutal Truth: Inside cloneWithoutBasedOn, you wrote this comment:

Java
c.setPayload(src.getPayload()); // JSON nodes are fine to share for our usage
They are absolutely not. Jackson's ObjectNode and ArrayNode are highly mutable. Because you cache materialized moxtures, if a developer retrieves the payload in their test and accidentally modifies it (e.g., ((ObjectNode) result.getBody()).put("hacked", true);), they have just permanently mutated the cached payload for every subsequent test that uses that moxture.

The Fix: Jackson provides a built-in deep copy. Use it to protect your cache:

Java
c.setPayload(src.getPayload() == null ? null : src.getPayload().deepCopy());
4. Severe Log Pollution (CI/CD Ruiner)
The Brutal Truth: Your HttpExecutor blasts log.info with giant ASCII banners for every single HTTP call:

Plaintext
[Moxter] >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>
[Moxter] >>> Executing moxture:  [create_pet, POST, /pets]
In a real-world enterprise suite with 500+ integration tests, this will generate tens of thousands of lines of useless ASCII art, completely ruining the readability of CI/CD build logs (like Jenkins or GitHub Actions).

The Fix: Build frameworks should be completely silent when tests pass, and only scream when they fail. Downgrade those ASCII banners to log.debug(), or add a .silent(true) toggle to MoxBuilder so enterprise users can shut the engine up.

5. ObjectMapper Churn
The Brutal Truth: Every time MoxBuilder.build() is called, you instantiate brand new Jackson ObjectMapper and YAMLFactory instances. Jackson mappers are notoriously heavy to instantiate and are explicitly designed to be static, thread-safe singletons.

The Fix: Move defaultYamlMapper() and defaultJsonMapper() to private static final constants in your IO class. Initialize them exactly once per JVM.





---
# Allow concurrent


    // Concurrent allows tests to be run in parallel
    //#private final ConcurrentMap<String, Object> vars = new ConcurrentHashMap<>();
    private final Map<String,Object> vars = new LinkedHashMap<>();




---
### define variables within moxture.yaml files:
```yaml
vars:
  USER1_ID   : "12345"
  USER2_ID   : "12"
  USER3_ID   : "123"
  USER4_ID   : "1234"
  LOCALE     : "EN"

moxtures:
...
```



---
### New syntax and check returned payload


```yaml
  - name: create_pet_for_owner
    method: POST
    endpoint: "/api/pets/{{petId}}"
    lax: true         # DEPRECATED, moved to the expected section
                      # Does not raise exception if status != 20x
                      # Useful in case this moxture is only "best effort" and we don't
                      # want to fail the whole test if this one fails.
    vars:
      in_petName: "Snowy"
    body: |
      {
        "name": "{{in_petName}}",
        "sex": "MALE",
        "birthDate": "2020-01-01",
        "species": {
          "name": "Dog"
        },
        "owner": {
          "id": "{{ownerId}}"
        }
      }
    save:
      failOnError:  true|false   # Toggles 'Lax' mode for JsonPath extractions.
                                # If false, failed JsonPath extractions will 
                                # return null 
                                # If true, execption is raised (and test will
                                # likely fail)
      vars:
        petId: "$.id"
        petName: "$.name"
        petOwnerId: "$.owner.id"
    expect:  # expect the return to be...
      failOnError: true|false   # Replaces lax. Optional. Default is true.
                                # If false do not raise exception in case expectations
                                # fail. (Just a warning maybe)
                                # Useful in case this moxture is only "best effort" and we don't
                                # want to fail the whole test if this one fails.
      status: 201     # possible values: null | int | "2xx"/"3xx"/"4xx"/"5xx" | "201" 
      body:

        # OPTION A: Surgical Assertions (Strict Equality)
        # Evaluates the JsonPath on the left and asserts it equals the value on the right.
        # Uses Jayway's built-in functions like .length() to avoid custom YAML operators.
        assert:
          # only use YAML asserts here for equality
          # For other more complicated types of asserts, do that on the Java side
          # Note: YAML parsers do not throw an error when they see duplicate keys.
          # They silently overwrite the first key with the second key
          # So we can't really define 2 asserts on one same jsonpath (the last one 
          # would always 'win')
          "$.item.type" : "new"   
          "$.item.value": 100
          "$.offers[*].length()": 3
          "$.offers[?(@.cost >= 1000 && @.cost <= 2000)].length()": 3
          "$.message": "Pet {{in_petName}} was created OK"  # strict equality check

          # Advanced Matches
          "$.message":                    # See note above.
            contains: "created OK"
          "$.uuid":
            matches: "^[a-f0-9\\-]{36}$"  # Regex for UUID
          "$.creationDate":
            exists: true                  # Just verify the node is there

        # OPTION B: Tree Matching (JSONAssert)
        match:
          mode: full|partial    # used in conjunction with 'content'
            # if full: match with json provided in value must be exact
            # if partial: provided json should be a subset of actual returned json
          ignorePaths: ["$.id"]    # ignore these 'volatile' field
          content: |  # the inline json expected
            {
              "type": "new",
              "value": "100"
            }

        # OPTION C: Contract Validation (JSON Schema)
        # Validates types and required fields without hardcoding volatile data.
        schema: "classpath:schemas/pet.json"  
          # schema checks the structure and data types of the response.
          # While the content (value) full/partial matching can be brittle
          # (think ids and dates that are highly variable), the schema checking
          # asserts the type of the response whic is supposed to be rigid.



```



---
### Should warn when using unknown/wrong yaml


---
### Improve logging/feedback

- "Debug Mode" that prints the full state of the ${vars} context whenever a call fails
- Conditional Logging
RestAssured has a genius feature: log().ifValidationFails().


---
### Better report

Generate full report :  
Here are the moxtures executed and the received returns


---
### Add warnings in the final report output

At the end: 

[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[WARN] Warnings:
[WARN]   Here we should have a list of warnings. Some might indeed
[WARN]   highlight cases where we're getting a pass for the wrong reasons.
[INFO]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------





---
### some kind of "Startup Banner" (that can be toggled on/off)

that prints what moxtures are available for the current class, where they're taken from, if there's any shadowing

```txt
--------------------------------------------------------
 MOXTER : PetIntegrationTest 
--------------------------------------------------------
 LOADED MOXTURES:
  [global]  login, get_health
  [local]   create_pet
  [SHADOW]  apply_shampoo (overriding global definition)
--------------------------------------------------------
```

---
### The "Collision Alert" (Safety without Complexity)
 (this can be a desired effect though, the warning should be just a "heads up") : " variable xyz defined in fixture f is overridden (what would be the right term btw? override/overwrite/shadow/... ?)




---
### Improve configuration

Maybe introduce a Configuration object


---
### Thread.sleep(xxx) in between moxture calls
when grouping moxtures: would be nice to be able to add Thread.sleep(xxx) in between


---
### Postman-style collection of moxtures?


---
### Check type conversion in vars

Advice: Make sure your engine handles Type Conversion. If $.id is an Integer in JSON, but needs to be a String in a later URL path, a "raw" replacement might fail. Adding a small type hint or auto-stringifying everything in {{vars}} will save you 100 bug reports later.


---
### "Circuit Breaker" Assertions
When grouping moxtures (e.g., a "Create, Update, Delete" flow), 
if the "Create" step fails, there is no point in trying to "Update."  
How it works: Introduce a failFast: true (default) at the group level.
Added Value: Stops the test execution immediately upon the first failure in a group, preventing a "log flood" of subsequent errors that are just side effects of the first one.  
Complexity: Very Low. Just a simple break in your group execution loop.


---
### failOnError, warnings...

Lax" Mode vs. failOnError
Deprecating lax in favor of a scoped failOnError shows professional evolution.

Hard Truth: Do not just "print a warning" if it fails. In a CI/CD pipeline, warnings are ignored. If failOnError is false, Moxter should log a Visual "Partial Success" (perhaps using that yellow circle 🟡 we discussed) so it stands out in the build logs without stopping the pipeline.

---
### Moxter Test Generator

Companies will pay for a Moxter Test Generator—a tool that watches their running app, "sniffs" the JSON traffic, and auto-generates these Moxture YAMLs for them. That is a $50k+ developer tool.

---
### Move away from JUnit as the driver of tests.  
Have Moxter itself be the driver of the tests


Solution A: MoxterStandaloneRunner
You would create a public static void main that:
Starts the Spring Context (using SpringApplication.run).
Grabs the MockMvc bean from the context.
Scans a directory (passed as a CLI argument) for .yaml moxture files.
Executes them and prints the "Startup Banner" and results.
Envisaged CLI Usage
Instead of clicking "Run JUnit Test" in IntelliJ, a developer (or a Jenkins pipe) would run:  
java -jar moxter-runner.jar --path=./src/test/resources/moxtures/smoke-suite.yaml

Solution B:The "Hybrid" Middle Ground
If you find full Standalone too complex, you could create a "Generic Moxter Test" in JUnit:

Java
@SpringBootTest
class MoxterUniversalRunner {
    @Autowired MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {"smoke.yaml", "regression.yaml"})
    void run(String fileName) {
        Moxter.forFile(fileName).mockMvc(mvc).execute();
    }
}
This keeps the JUnit engine but makes it invisible to the user—they just add YAML files to a folder and the "Universal Runner" picks them up.


Solution C: moxter maven plugin
The Maven Plugin is the natural evolution of the "Hybrid" approach. It is the "Professional" way to bridge the gap between a Java library and a standalone tool.

By creating a moxter-maven-plugin, you effectively turn your project into a Test Orchestrator.

1. How it would work
The plugin would act as a wrapper around your "Universal Runner." When a user runs mvn moxter:run, the plugin:

Bootstraps the project’s classpath (so it can see your Spring @Service and @Controller classes).

Starts a temporary Spring context.

Scans src/test/resources/moxtures for all YAML files.

Executes them and fails the build if any moxture fails.


---
### The "Moxter" 2026 Competitive Risk
The biggest threat in 2026 is AI-Generated Tests. Tools like Keploy or TestBooster.ai are trying to write tests automatically.

Counter-Move: Position Moxter as the "Human-Readable Source of Truth." AI-generated tests are often "black boxes." Moxter YAMLs are clear, versionable, and intentional.

Final Plausibility Score: 8/10
It is highly plausible if you focus on the Enterprise "Pain Points" (Maintenance, Speed, Onboarding) rather than just the "coolness" of the syntax.

The most logical first step: Create a "Consulting" landing page for Moxter. Don't wait for the tool to be perfect. If someone says "I'll pay you $2k to set this up for us," you've officially moved from "Dev" to "Founder."


---
### YAML schema

1. The "Whitespace and Typo" Tax
When a developer writes REST Assured, their IDE gives them auto-complete, and the Java compiler catches typos immediately.
If a developer writes "$.offers[*].lenght()": 3 in Moxter, or accidentally indents status: 200 one space too far, the IDE won't warn them. The test will just crash at runtime. You have traded compile-time safety for declarative readability.

The Mitigation: You must write a JSON Schema for your moxtures.yaml format and tell your developers to link it in their IDEs (IntelliJ/VSCode). This will give them autocomplete and red-squiggly lines for YAML typos. Without this, the Developer Experience (DX) will suffer.


---
### Abstract HTTP call engine
3. Ecosystem Lock-In (The MockMvc Coupling)
Right now, your engine HttpExecutor is hardcoded to Spring's MockMvc.

What if the QA team wants to run these exact same Moxter YAML files against a live staging environment on AWS? MockMvc can't do that (it mocks the servlet container).

To make Moxter a true competitor to Karate or Postman, you will eventually need to abstract HttpExecutor so you can swap MockMvc for a real HTTP client (like Spring RestClient or Java 11 HttpClient) using a toggle in MoxBuilder.

<br />
<br />
<br />
<br />

---
###  Table of features
<br />
<br />


| Feature | Added Value | Difficulty | Complexity Risk |
| :--- | :---: | :---: | :--- |
| **Fluent API** | ⭐⭐⭐⭐⭐ | Moderate | Low |
| **YAML Vars** | ⭐⭐⭐ | Easy | Low |
| **Syntax Refresh** | ⭐⭐ | Easy | Low |
| **JsonPath Assert** | ⭐⭐⭐⭐⭐ | Hard | Moderate |
| **Partial Match** | ⭐⭐⭐⭐ | Hard | Moderate |
| **Banner/Alerts** | ⭐⭐⭐⭐ | Easy | Low |
| **Wait/Sleep** | ⭐⭐ | Easy | Low |
| **Collision Alerts**| ⭐⭐⭐⭐ | Easy | Low |