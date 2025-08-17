# FixtureEngine — README

A tiny, copy-paste-friendly engine to drive your Spring `MockMvc` integration tests from a single `fixtures.yaml` file. Deterministic discovery, zero magic, great logs.

---

## TL;DR

- Put a `fixtures.yaml` next to your test class (see path rules below).
- In your test, build the engine with `FixtureEngine.forTestClass(getClass()).mockMvc(mockMvc).authentication(auth).build()`.
- Call fixtures by name (`fx.callFixture("create_bcs")`, `fx.callFixtureReturnId("create-offer")`) or run a group (`fx.callFixturesForGroup("BeforeAll")`).
- Payloads can be YAML/JSON objects, JSON strings, or `classpath:` includes; `{{var}}` templating works on strings.
- Responses can set variables via `save: { myId: $.id }` (JsonPath).
- Engine attaches auth per request and auto-adds CSRF on mutating verbs; no need to touch `SecurityContextHolder`.

---

## 1) File discovery

**Default location (no leading slash):**
```
classpath:/integrationtests/fixtures/{package}/{TestClassName}/fixtures.yaml
```
- `{package}`: your test’s Java package with dots → slashes  
- `{TestClassName}`: simple name (no nested class handling)

**If the file is missing**, you’ll get a clear failure like:
```
[FixtureEngine] No fixtures file found for com.example.MyTest
Expected at: classpath:/integrationtests/fixtures/com/example/MyTest/fixtures.yaml
Hint: place the file under src/test/resources/integrationtests/fixtures/com/example/MyTest/fixtures.yaml
```

### Reference tree
```
src/test/resources/
└─ integrationtests/
   └─ fixtures/
      └─ com/
         └─ your/
            └─ pkg/
               └─ CommercialOfferIntegrationTest2/
                  └─ fixtures.yaml
```

> **Groups are local-only.** Only the *closest* file (the one beside the test class) contributes `groups:`.  
> **Fixtures by name** (both direct calls and names listed inside groups) are resolved **hierarchically** (closest-first) up the package tree.

---

## 2) YAML format

### Top-level sections
- `groups:` (optional) – **only** in the closest file; no inheritance/merging of group policies.
- `fixtures:` – definitions (can be referenced by name from groups and from tests).

### Example

```yaml
groups:
  - name: BeforeAll
    fixtures:
      - create_bcs

  - name: AfterAll
    fixtures:
      - delete_bcs

fixtures:
  - name: create_bcs
    method: POST
    endpoint: /businessContractSheet
    expectedStatus: 201       # int or "2xx"/"3xx"/"4xx"/"5xx"
    payload:
      contractualStep: [70]
      subregionId: 1
      buyers: ["1-12A-1965","1-10D6XL"]
      contracts: []
    save:
      bcsId_1: $.id           # JsonPath → saves into vars

  - name: create-offer-a320_1
    method: POST
    endpoint: /businessContractSheet/{{bcsId_1}}/offer
    expectedStatus: 201
    payload: >
      {
        "aircraftSerieId": "A320-300N",
        "aircraftSpecification": "specification D",
        "aircraftType": "A320",
        "aircraftWeightVariant": "Extra : 297.0t / 279.2t",
        "catalogVersion": "Catalogue 2020, Issue 2",
        "firmQuantity": 5,
        "optionQuantity": 4,
        "purchaseRightsQuantity": 3,
        "title": "A320"
      }
    save:
      offer1Id: $.id

  - name: create-offer-a320_2
    basedOn: create-offer-a320_1   # same-file deep merge (see merge rules)
    save:
      offer2Id: $.id

  - name: create-offer-a350
    basedOn: create-offer-a320_1
    payload: >
      { "aircraftSerieId": "A350-300N", "aircraftType": "A350", "title": "A350" }
    save:
      offer3Id: $.id

  - name: delete_bcs
    method: DELETE
    endpoint: /businessContractSheet/{{bcsId_1}}/delete
    expectedStatus: 200
```

### What’s supported

- **HTTP method**: `GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS`
- **endpoint**: string with `{{var}}` placeholders
- **headers** / **query** (optional): maps; templating applies to values
- **payload**:
  - YAML object/array (templating on string leaves)
  - JSON string (engine parses)
  - `classpath:relative/path/to/payload.json|yaml` (relative to the fixtures file folder)
- **expectedStatus** (optional): either an **int** (e.g. `201`) or a coarse class (`"2xx"`, `"4xx"`, …)
- **save** (optional): map of `varName: $.json.path` (JsonPath) to store into the shared `vars` map
- **basedOn** (optional): **same-file** name of a base fixture; deep-merge rules below

---

## 3) Fixture lookup examples (hierarchical)

Assume the following files:

```
src/test/resources/integrationtests/fixtures/com/acme/sales/order/OrderApiIT/fixtures.yaml
src/test/resources/integrationtests/fixtures/com/acme/sales/order/fixtures.yaml
src/test/resources/integrationtests/fixtures/com/acme/sales/fixtures.yaml
```

Content snippets:

**`.../sales/fixtures.yaml`**
```yaml
fixtures:
  - name: create_bcs
    method: POST
    endpoint: /businessContractSheet
    expectedStatus: 201
```

**`.../sales/order/fixtures.yaml`**
```yaml
fixtures:
  - name: create_bcs
    method: POST
    endpoint: /businessContractSheet?source=order   # overrides the higher-level one
    expectedStatus: 201
```

**`.../sales/order/OrderApiIT/fixtures.yaml`**
```yaml
groups:
  - name: BeforeAll
    fixtures: [ create_bcs, create_order_specific ]

fixtures:
  - name: create_order_specific
    method: POST
    endpoint: /order/init
    expectedStatus: 201
```

### Resolution behavior
- `fx.callFixture("create_bcs")` in `OrderApiIT` resolves to the **closest** definition:
  - first looks in `.../order/OrderApiIT/fixtures.yaml` (no match),
  - then `.../order/fixtures.yaml` (**match — used**),
  - stops without going up to `.../sales/fixtures.yaml`.
- Group `"BeforeAll"` runs names in order. For `create_bcs`, the same **closest-first** resolution applies. Unknown names → fail with the name and the file that referenced it.

---

## 4) `basedOn` payload merge — how it’s computed

> **V1 scope:** `basedOn` resolves **within the same file** only. (A future version may allow hierarchical resolution.)

Deep-merge rules:
- Scalars (`method`, `endpoint`, `expectedStatus`) → child overrides if present.
- Maps (`headers`, `query`, `save`) → shallow merge, child keys override parent.
- `payload`:
  - If both parent & child are **objects** → deep-merge recursively (child wins on conflicts).
  - If either side is an **array or scalar** → child **replaces** parent entirely.
  - If child payload is a **JSON string**, it’s parsed to an object **before** merging.

### Example

```yaml
fixtures:
  - name: base_offer
    method: POST
    endpoint: /businessContractSheet/{{bcsId_1}}/offer
    expectedStatus: 201
    headers:
      X-Trace: "base"
    payload:
      aircraftType: A320
      details:
        weight: "279.2t"
        seats: 180
        options: [ "wifi", "seatpower" ]

  - name: offer_a350
    basedOn: base_offer
    headers:
      X-Trace: "child"             # overrides header
    payload: >
      {                             # JSON string gets parsed then merged
        "aircraftType": "A350",
        "details": {
          "seats": 300,             # overrides
          "options": ["wifi"]       # array → replaces parent array
        }
      }
```

**Effective payload for `offer_a350`:**
```json
{
  "aircraftType": "A350",
  "details": {
    "weight": "279.2t",       // preserved from parent (object deep-merge)
    "seats": 300,             // overridden by child
    "options": ["wifi"]       // array replaced by child
  }
}
```

---

## 5) Variables & templating — how to set and use

There’s a single shared `Map<String,Object>` of variables per engine (`fx.vars()`).

### A) Setting variables from responses
```yaml
fixtures:
  - name: create_bcs
    method: POST
    endpoint: /businessContractSheet
    expectedStatus: 201
    payload: { contractualStep: [70], subregionId: 1, buyers: ["1-12A-1965","1-10D6XL"], contracts: [] }
    save:
      bcsId_1: $.id          # JsonPath into the response body
```

After running `fx.callFixture("create_bcs")`, the engine will have:
- `vars().get("bcsId_1")` → the created ID
- convenience stashes when you use `callFixtureReturn(...)`:
  - `_last` contains the extracted value
  - top-level key from your JsonPath (e.g. `id` for `$.id`)
  - `<fixtureName>.<key>` (e.g. `create_bcs.id`)

### B) Using variables in YAML (templating)
You can interpolate `{{var}}` in:
- `endpoint`
- header values
- query values
- all string leaves inside `payload` (YAML/JSON)

```yaml
fixtures:
  - name: create_offer
    method: POST
    endpoint: /businessContractSheet/{{bcsId_1}}/offer
    expectedStatus: 201
    payload:
      title: "Offer for BCS {{bcsId_1}}"
      options:
        - "{{someFlag}}"        # will be replaced if set in vars

  - name: delete_bcs
    method: DELETE
    endpoint: /businessContractSheet/{{bcsId_1}}/delete
    expectedStatus: 200
```

You can also pre-seed variables in code:
```java
fx.vars().put("someFlag", "wifi");
```

### C) Getting variables in Java
```java
Object any = fx.vars().get("bcsId_1");
long id = Long.parseLong(String.valueOf(any));

// or use convenience extractors during execution:
long offerId = fx.callFixtureReturnId("create_offer");
Object title = fx.callFixtureReturn("create_offer", "$.title");  // also stashes _last and keys
```

---

## 6) Authentication & CSRF

- Provide an `Authentication` when building:
  ```java
  fx = FixtureEngine.forTestClass(getClass())
       .mockMvc(mockMvc)
       .authentication(getTestAuthentication())     // or .authenticationSupplier(this::getTestAuthentication)
       .build();
  ```
- The engine attaches that auth to **every** request and auto-adds **CSRF** for `POST/PUT/PATCH/DELETE`.  
  You **don’t** need to set `SecurityContextHolder`.

> If you manually call services (not via MockMvc) that rely on `SecurityContextHolder`, set/clear it in your test’s `@BeforeEach/@AfterEach`.

---

## 7) Logging & diagnostics

Readable, compact logs:

- **Start**:
  ```
  [FixtureEngine] >>> Executing fixture: [create_bcs, POST, /businessContractSheet]
  ```
- **DEBUG request preview** (if enabled):
  ```
  [FixtureEngine] more info: expected=201 headers={} query={} vars={...} payload={ ... }
  ```
- **Response preview** (DEBUG):
  ```
  [FixtureEngine] response preview: status=201 headers={...} body={...}
  ```
- **Finish**:
  ```
  [FixtureEngine] <<< Finished executing fixture: [create_bcs, POST, /businessContractSheet] with status: [201], in 187 ms
  ```
- **Mismatch**:
  ```
  [FixtureEngine] Unexpected HTTP 400 for 'create_bcs' POST /businessContractSheet, expected=201
  [FixtureEngine] Body: {"type":"about:blank","title":"Bad Request",...}
  ```

Enable extra debug traces by running with:
```
-Dfixtureengine.debug=true
```

---

## 8) Using in tests

### Option A: Base class (recommended)

`ParentIntegrationTest` keeps setup consistent and tiny:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles({"test"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ParentIntegrationTest {
  @Autowired protected MockMvc mockMvc;
  @Autowired protected AirbusUserRepository airbusUserRepository;
  protected FixtureEngine fx;

  @BeforeAll
  void bootBase() {
    fx = FixtureEngine.forTestClass(getClass())
        .mockMvc(mockMvc)
        .authentication(getTestAuthentication())
        .build();
    fx.callFixturesForGroup("BeforeAll");
  }

  @AfterAll
  void teardownBase() {
    fx.callFixturesForGroup("AfterAll");
  }

  protected Authentication getTestAuthentication() {
    var user = airbusUserRepository.findUserByUserId("local");
    return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
  }
}
```

Subclass:

```java
class CommercialOfferIntegrationTest2 extends ParentIntegrationTest {

  @Test
  void createsOffers() throws Exception {
    long offer1 = fx.callFixtureReturnId("create-offer-a320_1");
    long offer2 = fx.callFixtureReturnId("create-offer-a320_2");
    long offer3 = fx.callFixtureReturnId("create-offer-a350");
    // ...assertions...
  }

  @BeforeEach void beforeEach() { /* fx.callFixturesForGroup("BeforeEach"); */ }
  @AfterEach  void afterEach()  { /* fx.callFixturesForGroup("AfterEach");  */ }
}
```

### Option B: Ad-hoc usage

If you don’t want a base class:

```java
FixtureEngine fx = FixtureEngine.forTestClass(getClass())
    .mockMvc(mockMvc)
    .authentication(getTestAuthentication())
    .build();

fx.callFixturesForGroup("BeforeAll");
long id = fx.callFixtureReturnId("create_bcs");
```

---

## 9) Troubleshooting

**401 Unauthorized on some calls**
- Ensure you pass `.authentication(...)` or `.authenticationSupplier(...)` to the builder.
- If your app requires CSRF for mutating verbs, the engine already adds it. If you still get 403/401, check your security config or required headers.

**“No fixtures file found … Expected at: classpath:/…”**
- Create the file at exactly that path under `src/test/resources/…`.

**“Fixture not found: … Available: …”**
- Name typo, or you expected a higher-level file that doesn’t declare that fixture. Remember: closest definition wins; search climbs up.

**basedOn doesn’t override payload as expected**
- If the child payload is a **JSON string**, it is **parsed first** and then merged (deep object merge; arrays replace). That’s the intended behavior.

---

## 10) API cheatsheet

```java
// Engine
FixtureEngine.forTestClass(getClass())
    .mockMvc(mockMvc)
    .authentication(auth)                      // or .authenticationSupplier(() -> auth)
    .build();

// Groups
fx.callFixturesForGroup("BeforeAll");         // runs names, resolved by hierarchy

// Named calls
fx.callFixture("create_bcs");                 // returns ResponseEnvelope
fx.callFixtureReturnId("create-offer-a320_1");// $.id as long
Object v = fx.callFixtureReturn("...", "$.path"); // stash vars: _last, id, name.id

// Vars
fx.vars().put("token", "abc");                // used by {{token}} templating

// Response envelope
int status = env.status();
JsonNode body = env.body();
String raw   = env.raw();
```
