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
# Allow concurrent


    // Concurrent allows tests to be run in parallel
    //#private final ConcurrentMap<String, Object> vars = new ConcurrentHashMap<>();
    private final Map<String,Object> vars = new LinkedHashMap<>();



---
# rationalize scopes ? 


moxter.vars
localVars = moxter.resolveByName(moxtureName).moxt.getVars();
callVars


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
### new moxture syntax 

BEFORE
```yaml
  - name: update_item
    method: PUT
    endpoint: /item/update
    expectedStatus: 200
    payload: >
      [
        {
          "objectClass": "ThirdParty",
          "eventType": "FieldUpdateEvent",
          "objectId": {{com.thirdPartyId}},
          "parentId": {{com.bcsId}},
          "fieldName": "notRelevant",
          "fieldValue": {{notRelevant}}
        }
      ]
```
AFTER

```yaml
  - name: update_item
    method: PUT
    url: /item/update   # instead of 'endpoint'
    body:  >   # instead of 'payload'
      [ ...
    save:      # keep
    expect:
      status:   # instead of expectedStatus
      body: >   
        # json return payload
```

---
### check returned payload


```yaml
  - name: update_item
  ...
    body:
      assert:  # selectively assert jsonpaths
        $.item.type: "new"   
        $.item.value: 100
        $.offers[*]:
           count: 3
        $offers[?(@.cost >= 1000 && @.cost <= 2000]:
           minCount:1
           maxCount:3
        $.createdAt[?(@ == 'today')]

      # OR/AND use full response matching:
      match: full|partial 
        # if full: match with json provided in value must be exact
        # if partial: provided json should be a subset of actual returned json
      value: >  # the innline json expected
      [
        {
          "type": "new",
          "value": "100",
```


---
### Improve logging/feedback

- "Debug Mode" that prints the full state of the ${vars} context whenever a call fails
- Conditional Logging
RestAssured has a genius feature: log().ifValidationFails().


---
### Improve configuration

Maybe introduce a Configuration object


---
### Thread.sleep(xxx) in between moxture calls
when grouping moxtures: would be nice to be able to add Thread.sleep(xxx) in between

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
### Postman-style collection of moxtures?


---
### "Circuit Breaker" Assertions
When grouping moxtures (e.g., a "Create, Update, Delete" flow), if the "Create" step fails, there is no point in trying to "Update."  
How it works: Introduce a failFast: true (default) at the group level.
Added Value: Stops the test execution immediately upon the first failure in a group, preventing a "log flood" of subsequent errors that are just side effects of the first one.  
Complexity: Very Low. Just a simple break in your group execution loop.


---
### Better report

Generate full report :  
Here are the moxtures executed and the received returns



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