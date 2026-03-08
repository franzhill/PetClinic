




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

The Fix: Build frameworks should be completely silent when tests pass, and only scream when they fail. Downgrade those ASCII banners to log. debug(), or add a .silent(true) toggle to MoxBuilder so enterprise users can shut the engine up.




1. The "Must-Fix" Architecture (Priority: Critical)    # TODO
If you don't do these, your engine is a toy, not a tool.

The Payload Trap (.deepCopy()): This is a Level 1 Bug. In a multi-threaded test environment (like parallel JUnit execution), shared mutable JsonNode objects will cause non-deterministic failures that are impossible to debug. Fix this today.

ObjectMapper Churn: Instantiating a YAMLFactory per build is amateur hour. These should be private static final constants. Period.

5. ObjectMapper Churn  # TODO
The Brutal Truth: Every time MoxBuilder.build() is called, you instantiate brand new Jackson ObjectMapper and YAMLFactory instances. Jackson mappers are notoriously heavy to instantiate and are explicitly designed to be static, thread-safe singletons.

The Fix: Move defaultYamlMapper() and defaultJsonMapper() to private static final constants in your IO class. Initialize them exactly once per JVM.



The ASCII "Banner" Pollution: Big ASCII art in logs is the "Comic Sans" of the DevOps world. It looks "cool" for the first 5 tests; it's a war crime after 500. Jenkins logs will become gigabytes of garbage. Downgrade to DEBUG level immediately.









---
### Moxture DX Features #TODO


```yaml

#TODO
include:
  - "common_moxtures.yaml"
  - "common_vars.yaml"
  - "creation_moxtures.yaml"


# Define these global variables # TODO
vars:
  USER1_ID   : "12345"
  USER2_ID   : "12"
  USER3_ID   : "123"
  USER4_ID   : "1234"
  LOCALE     : "EN"


moxtures:

  # --------------------------------------------------------
  # A 'single' moxture
  # --------------------------------------------------------

  - name: create_pet_for_owner
    method: POST
    endpoint: "/api/pets/{{petId}}"  # allow interpolation with vars from the global context
    lax: true         # DEPRECATED, moved to the expected section
                      # Does not raise exception if status != 20x
                      # Useful in case this moxture is only "best effort" and we don't
                      # want to fail the whole test if this one fails.
    vars:             # Default variables, can be overriden by call scope.
      in.petName: "Snowy"
      in.petSex : {{sex}}    # allow interpolation...
    body: |                  # allow interpolation...
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
      failOnError:  true|false  # TODO. Toggles 'Lax' mode for JsonPath extractions.
                                # If false, failed JsonPath extractions will 
                                # return null 
                                # If true, execption is raised (and test will
                                # likely fail)
      vars:                     # Store vars in the global context
        petId: "$.id"
        petName: "$.name"
        petOwnerId: "$.owner.id"
    expect:  # expect the return to be...
      # TODO: test, vs lax
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
          # TODO: tell Jackson to deserialize assert as a List<Map<String, Object>> 
          # instead of a flat Map. (this means placing a "-" in front of the following
          # elements). 
          # This allows you to assert against the same JSON path multiple times sequentially.
          - "$.item.type" : "new"   
          - "$.item.value": 100
          - "$.offers[*].length()": 3
          - "$.offers[?(@.cost >= 1000 && @.cost <= 2000)].length()": 3
          - "$.message": "Pet {{in_petName}} was created OK"  # strict equality check


          # Advanced Matches
          - "$.message":                    # See note above.
            contains: "created OK"
          - "$.uuid":
            matches: "^[a-f0-9\\-]{36}$"  # Regex for UUID
          - "$.creationDate":
            exists: true                  # Just verify the node is there

        # OPTION B: Tree Matching (JSONAssert)
        match:
          mode: full|partial    # used in conjunction with 'content' # TODO full
            # if full: match with json provided in value must be exact
            # if partial: provided json should be a subset of actual returned json
          ignorePaths: ["$.id"]    # TODO ignore these 'volatile' field
          content: |  # the inline json expected
            {
              "type": "new",
              "value": "100"
            }

        # OPTION C: Contract Validation (JSON Schema) # TODO
        # Validates types and required fields without hardcoding volatile data.
        schema: "classpath:schemas/pet.json"  
          # schema checks the structure and data types of the response.
          # While the content (value) full/partial matching can be brittle
          # (think ids and dates that are highly variable), the schema checking
          # asserts the type of the response whic is supposed to be rigid.



  # --------------------------------------------------------
  # A 'group' moxture
  # --------------------------------------------------------
  # TODO 
  - name: group_create_owner_and_pet
    steps:   # The attributes under here must echo the commands available to mx.caller()
      - call: create_owner                   # Simple moxture call by name. Saves {{ownerId}} globally
      - vars:                                # Interaction with global scope:
          owner: "{{ownerId}}"               #   Swap/Rename/Pipe (for the next moxtures)
          debug_label: "Created_at_{{now}}"  #   Or create new global context
      - call: create_pet_for_owner
      - sleep: 500ms                         # For asynchronous testing
      - call: create_other                   # Call a moxture with added context.
        with:                                # => add variables to the call scope
          in.name: "Rex"                     #   Override default
          in_owner_id: "{{owner}}"           #   Or use from global context
    finally:                                 # To prevent the scenario from cluttering the environment
      - call ...                             # these calls are exectued by Moxter whatever the
      - call ...                             # outcome of the scenario (i.e. event if it fails at step 2)

# TODO
# If called directly this file will run these moxtures in the following order:
# E.g. doing:
#   mvn moxter:run -Dfile=tests_pet_crud.yaml
# or programatically:
#   mx.build().load("tests_pet_crud.yaml").play()
# will automatically look for this section and execute the moxtures in the order 
# they appear.
# Any complex orchestration logic can be stowed away in the moxture (single or group)
#
# We could also provide this: 
#   mvn moxter:run -Dfile=tests.yaml -Dmoxture=smoke_test_checkout
# => runs the target moxture
#
# IF devs/QAS want to have different test_suites they can just create a seperate file
# for each and include the necessary moxtures.
#
play:
  - moxture_1
  - moxture_2
  - moxture_3


```

Not doing : 

```yaml

  - name: create_pet_for_owner
    expect:  # expect the return to be...
      body:
        assert:
        # Assert exception should be thrown: # TODO does this make sense? A REST call does 
                                             # not return an execption and we can assert
                                             # return status and check the message
          exception:
            type: "MatingException"           # Unwraps the cause chain to find this class
            messageContains: "is sterile"     # Convenience check for the message
            assert:                           # Surgical JSONPath asserts on the Exception object!
              "$.causeEnum": "STERILE_PARENT"
```

---
### Different ways to orchestrate the tests   #TODO

@Test
void myTest
{ mx = Moxter.build().caller();
  mx.call("moxture_1");
  mx.call("moxture_2");
  mx.call("moxture_3");
}  


@Test
void myTest
{ mx = Moxter.build().caller();
  mx.call("moxture_1");
  mx.call("group_moxture_1");
  mx.call("moxture_3");
}  


@Test
void myTest
{ mx = Moxter.build()
             .load("file_with_moxtures.yaml");  // with includes
  mx.call("moxture_1");
  mx.call("group_moxture_1");
  mx.call("moxture_3");
}  


@Test
void myTest
{ mx = Moxter.build()
             .load("file_with_moxtures.yaml");  // with includes
             .run();       // Execute what's in the run section
}  


---
### Distinguishing between single and group moxture   #TODO


Polymorphic Moxture Routing: Duck Typing vs. Explicit Types
To seamlessly differentiate between single (HTTP) and group moxtures within the same YAML array without requiring boilerplate type attributes, there are 2 possibilities : 
- Duck Typing (Jackson Deduction: if there is a step attribute then it's a group) 
   COns:  yields cryptic, unhelpful errors when users make typos (e.g., enpoint). 
   Mitigated if we have YAML Schema Validator
- Strict (force providing a "type: single/group" attribute. Could be by default single so we dont
  have to specify for single moxtures)

Since we're not sure what way to go we can use a hybrid approach in the code. The  logic is abstracted into a custom MoxtureRoutingDeserializer. This mechanism checks for an explicit type override first, but gracefully falls back to inspecting the object's shape (e.g., routing to a Group if steps are present).
 


---
### formal YAML/JSON Schema validation   #TODO


1. The "Whitespace and Typo" Tax
When a developer writes REST Assured, their IDE gives them auto-complete, and the Java compiler catches typos immediately.
If a developer writes "$.offers[*].lenght()": 3 in Moxter, or accidentally indents status: 200 one space too far, the IDE won't warn them. The test will just crash at runtime. You have traded compile-time safety for declarative readability.

The Mitigation: You must write a JSON Schema for your moxtures.yaml format and tell your developers to link it in their IDEs (IntelliJ/VSCode). This will give them autocomplete and red-squiggly lines for YAML typos. Without this, the Developer Experience (DX) will suffer.




---
### Override with jsonpath  #TODO

mx.caller()
  // Override variable
  .with("in.name"   , "Random_afFik874987")
  // Override body directly with jsonpath
  .with("$.species.sex"    , "MALE" )   # if begins with $ it's a jsonpath
  .call(moxtureName);

or name it 

  .override("$.species.sex"    , "MALE" )   # if begins with $ it's a jsonpath


=> 

mx.caller()
  .with("in_name", "Random_afFik874987")  // "Interpolate this variable"
  .override("$.species.sex", "MALE")      // "Surgically override this JSON path"
  .call(moxtureName);





==============================================================================================
---
## ERROR MANAGEMENT / PREVENTION

---
### The "Collision Alert" (Safety without Complexity)
 (this can be a desired effect though, the warning should be just a "heads up") : " variable xyz defined in fixture f is overridden (what would be the right term btw? override/overwrite/shadow/... ?)



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
### Moxtures should not ALL be evaluated at start of Moxter engine  #TODO

If a moxture is faulty then Moxter crashes
When the faulty moxture might not ever end up being called at all.



==============================================================================================
---
### Improve ERROR/DEBUGGING

We reeally need to improve syntax error support in the moxtures yaml files
Th error reported needs to clearly indicate the file and the line

Instead of the following, we should have a clear message indicating
- which moxture is faulty
- where exactly


12:23:52.229 main INFO  o.s.t.w.s.TestDispatcherServlet initServletBean,554 : Completed initialization in 1 ms
12:23:52.263 main INFO  c.f.p.m.controller.CrudTest logStarted,56 : Started CrudTest in 10.863 seconds (process running for 12.388)
[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 11.81 s <<< FAILURE! -- in com.fhi.pet_clinic.moxter_tests.controller.CrudTest
[ERROR] com.fhi.pet_clinic.moxter_tests.controller.CrudTest -- Time elapsed: 11.81 s <<< ERROR!
java.lang.RuntimeException: Failed reading vars from moxtures/com/fhi/pet_clinic/moxtures.yaml
        at com.fhi.moxter.Moxter.loadHierarchicalVars(Moxter.java:393)
        at com.fhi.moxter.Moxter.<init>(Moxter.java:296)
        at com.fhi.moxter.Moxter$MoxBuilder.build(Moxter.java:812)
        at com.fhi.pet_clinic.moxter_tests.ParentMoxterTest.bootBase(ParentMoxterTest.java:75)
        at java.base/java.lang.reflect.Method.invoke(Method.java:568)
        at java.base/java.util.ArrayList.forEach(ArrayList.java:1511)
        Suppressed: java.lang.NullPointerException: Cannot invoke "com.fhi.moxter.Moxter.caller()" because "this.mx" is null
                at com.fhi.pet_clinic.moxter_tests.ParentMoxterTest.teardownBase(ParentMoxterTest.java:85)
                at java.base/java.lang.reflect.Method.invoke(Method.java:568)
                at java.base/java.util.ArrayList.forEach(ArrayList.java:1511)
                at java.base/java.util.Collections$UnmodifiableCollection.forEach(Collections.java:1092)
                ... 1 more
Caused by: com.fasterxml.jackson.databind.JsonMappingException: Expected a field name (Scalar value in YAML), got this instead: <org.yaml.snakeyaml.events.MappingStartEvent(anchor=null, tag=null, implicit=true)>
 at [Source: (BufferedInputStream); line: 79, column: 32] (through reference chain: com.fhi.moxter.Moxter$Model$MoxtureFile["moxtures"]->java.util.ArrayList[3]->com.fhi.moxter.Moxter$Model$Moxture["expect"]->com.fhi.moxter.Moxter$Model$ExpectDef["body"]->com.fhi.moxter.Moxter$Model$ExpectBodyDef["assert"]->com.fhi.moxter.Moxter$Model$ExpectBodyAssertDef["$.[0].mother.id"])

It is absolutely possible, and it’s a crucial upgrade for Moxter's Developer Experience (DX).

Right now, Jackson (and SnakeYAML underneath it) is reading your file as a BufferedInputStream. Because it's just reading a stream of bytes, it loses the context of the file name and outputs the generic [Source: (BufferedInputStream); line: 79, column: 32].

To fix this, we need to catch Jackson's JsonProcessingException, extract the exact JsonLocation (which holds the line and column data), and combine it with the file path we already know to throw a beautifully formatted, highly visible error.

---
### Improve logging/feedback

- "Debug Mode" that prints the full state of the ${vars} context whenever a call fails
- Conditional Logging
RestAssured has a genius feature: log().ifValidationFails().


==============================================================================================
---
## REPORTING


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



==============================================================================================
---
## ENGINE

---
# Allow concurrent


    // Concurrent allows tests to be run in parallel
    //#private final ConcurrentMap<String, Object> vars = new ConcurrentHashMap<>();
    private final Map<String,Object> vars = new LinkedHashMap<>();


---
### Improve configuration

Maybe introduce a Configuration object
Maybe have the config in a yaml file


---
### Abstract HTTP call engine   #TODO
3. Ecosystem Lock-In (The MockMvc Coupling)
Right now, your engine HttpExecutor is hardcoded to Spring's MockMvc.

What if the QA team wants to run these exact same Moxter YAML files against a live staging environment on AWS? MockMvc can't do that (it mocks the servlet container).

To make Moxter a true competitor to Karate or Postman, you will eventually need to abstract HttpExecutor so you can swap MockMvc for a real HTTP client (like Spring RestClient or Java 11 HttpClient) using a toggle in MoxBuilder.

<br />
<br />
<br />
<br />



==============================================================================================
---
## BRINGING MOXTER INTO ANOTHER DIMENSION

---
### Maven plugin









==============================================================================================
---
## OTHER



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
###  Priorities
<br />
<br />



🚨 Priority 1: Critical Fixes & DX Foundations (Do Next)
These are silent killers. They either introduce flaky tests, cripple performance, or cause developer frustration.

1. Shared Mutable State (The Payload Trap)

Evaluation: Critical. Right now, Jackson ObjectNode caching means one test mutating a payload will corrupt it for all subsequent tests.

Action: This must be fixed immediately by invoking .deepCopy() when resolving/passing payloads from the cache.

2. ObjectMapper Churn

Evaluation: High. Instantiating a new Jackson ObjectMapper and YAMLFactory on every MoxBuilder.build() is a massive performance drain. They are designed to be static singletons.

Action: Move them to private static final constants immediately.

3. YAML JSON Schema (The "Whitespace Tax")

Evaluation: High. Without an IDE schema, developers will misspell keys (e.g., lenght() instead of length()) and not know until runtime.

Action: Generate a standard JSON Schema (moxter-schema.json) so VSCode and IntelliJ can provide real-time autocomplete and validation.

4. Severe Log Pollution

Evaluation: High. The giant ASCII banners for every call will make Jenkins/GitHub Actions logs completely unreadable in a suite of 500+ tests.

Action: Add a .silent(true) toggle to MoxBuilder or downgrade the ASCII banners to log.debug().

🟡 Priority 2: Core Execution & Flow Control
These features make the YAML engine powerful enough to handle complex, real-world testing scenarios.

1. "Circuit Breaker" Assertions (failFast)

Evaluation: Excellent addition. If step 1 (Create) fails, step 2 (Update) will inevitably fail.

Action: Add failFast: true|false at the steps: level to immediately halt the group on the first failure, preventing log floods.

2. Thread.sleep(xxx) (Delays)

Evaluation: Essential for testing async processes (e.g., waiting for an event to publish before checking the DB).

Action: Add a - delay: 2000 or - sleep: 2s command to our new steps grammar.

3. failOnError & Partial Success (Warnings)

Evaluation: Replaces the vague lax: true. If a test is allowed to fail, it shouldn't just vanish into the logs; it needs a visual 🟡 indicator so the pipeline passes but QA is alerted.

4. Concurrent execution map

Evaluation: Swapping LinkedHashMap for ConcurrentHashMap in global vars is necessary if tests run in parallel. However, be careful: true parallel execution of shared global state requires sophisticated scoping (e.g., thread-local variables).

🔵 Priority 3: Reporting & Visibility
These features elevate Moxter from a "library" to a "testing framework."

1. Startup Banner & Collision Alerts

Evaluation: Showing which moxtures are loaded and explicitly warning when a local moxture "shadows" a global one is a massive debugging aid.

Action: Print a clean, toggleable banner at context startup.

2. Better Final Report

Evaluation: A consolidated summary at the end of the test run showing Warnings, Skipped, and Executed moxtures.

Action: Easily achieved via a JUnit TestExecutionListener or a static reporting hook.

3. "Debug Mode" for Vars

Evaluation: Automatically dumping the current {{vars}} state to the console only when an HTTP call fails saves hours of manual debugging.

🟣 Priority 4: Architectural Strategic Shifts
These are the "V2" features that will make Moxter a standalone enterprise product, competing with Karate or Postman.

1. Abstract HTTP call engine (Ecosystem Lock-In)

Evaluation: Massive strategic value. Decoupling from MockMvc allows QA teams to use Moxter YAMLs against live, deployed staging environments using Java HttpClient or Spring RestClient.

2. Maven Plugin / Standalone Runner

Evaluation: Moving away from JUnit as the driver. Running mvn moxter:run turns this into a true orchestrator.

3. Moxter Test Generator (Traffic Sniffing)

Evaluation: The ultimate endgame. A proxy that records manual clicks in an app and auto-generates Moxter YAML.