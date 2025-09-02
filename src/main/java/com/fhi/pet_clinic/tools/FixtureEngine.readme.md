# FixtureEngine — README

**FixtureEngine** is a lightweight, copy-paste-friendly utility for driving Spring `MockMvc` integration tests from YAML fixture files.  

It provides a declarative way to describe test setup and teardown steps (fixtures) and execute them consistently across your tests.

Instead of scattering `MockMvc` calls and JSON boilerplate throughout tests, they can be centralized in YAML files, and then called by name from within the test code. This makes tests shorter, more readable, and easier to maintain.



## TL;DR

- A **fixture** is a declarative configuration for an HTTP call executed via `MockMvc`.
- Fixtures are defined in `fixtures.yaml` files (one next to your test class; see path rules below).
- Fixtures are callable from JUnit tests.
- In your JUnit tests, build the engine with:  
  `FixtureEngine.forTestClass(getClass()).mockMvc(mockMvc).authentication(auth).build()`
- Then call fixtures by name with:  
  `fx.callFixture("create_bcs")`, `fx.callFixtureReturnId("create-offer")`, …
- Payloads can be YAML/JSON objects, JSON strings, or `classpath:` includes.
- Responses can save variables via JsonPath:  
  `save: { myId: $.id }` → variables can be reused in other fixtures or retrieved in tests.
- Fixtures can also be grouped and executed together just like a single fixture.
- Auth can be provided to the engine so that it is automatically attached per request and CSRF tokens auto-added on mutating verbs; no need to touch `SecurityContextHolder`.

<br />



## 1) YAML fixture file

### What it is
Holds the definition/configuration of fixtures, which are essentially http requests that can easily be called from within the JUnit test.

### Example

```yaml
fixtures:
  - name: BeforeAll  # This fixture is a "fixture group" because it declares a 
                     # "fixtures" field (which may be empty ([]) if necessary,
                     # for convenience)
                     # Fixture names are purely symbolic — they have no built-in meaning.
                     # Here this name was chosen by convention/for readability.
    fixtures:
      # Execute these fixtures in the order they are listed
      - common.BeforeAll # this is a "common" fixture that is defined in a higher
                         # level fixtures.yaml, so it can available in any test.
                         # The "common." prefix is purely a naming convention chosen
                         # here to create an artificial namespace and avoid collisions.
      - create_order     # This is a fixture that is defined in this file.

  - name: AfterAll  # Teardown fixture group
    fixtures:
      # Notice teardown steps usually mirror setup steps, but in reverse order.
      - delete_order     # local fixture
      - common.AfterAll  # fixture defined in a higher level fixture file.


  - name: create-order
    method: POST
    endpoint: /order
    expectedStatus: 201       # also acceptable:
                              #  2xx
                              #  [200, 201, 202]
    payload:  # Several formats are supported. Here the yaml format is used.
      item: [70]
      subregionId: 1
      buyers: ["Alice","Bob"]
      contracts: []
    save:
      order_1: $.id   # Save the id field of the response in the var "order_1"

  - name: create-order-item
    method: POST
    endpoint: /order/{{order_1}}/item
    expectedStatus: 201
    payload: >    # Here the json format is used.
      {
        "sku": "SKU-12345",
        "name": "Wireless Headphones",
        "quantity": 2,
        "unitPrice": 89.99,
        "currency": "EUR",
        "category": "Audio",
        "attributes": 
          { "color": "Black",
            "warrantyMonths": 24
          }
      }
    save:
      item1Id: $.id

  - name: create-order-item_2
    basedOn: create-order-item   # inherit settings from this fixture.
    save:                        # save is NOT inherited.
      item2Id: $.id

  - name: create-order-item_3
    basedOn: create-order-item
    payload: >     # For this one, payload is the same except for the "attributes"
      { "attributes": 
        { "color": "Red",
          "warrantyMonths": 12
        }
      }
    save:
      item2Id: $.id

  - name: delete_order
    method: DELETE
    endpoint: /order/{{order_1}}/item
    expectedStatus: 200
```


### Supported fields for a fixture

- **HTTP method**: `GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS`
- **endpoint**: string with `{{var}}` placeholders
- **headers** / **query** (optional): maps; templating applies to values
- **payload**:
  - YAML object/array (templating on string leaves)
  - JSON string (engine parses)
  - `classpath:relative/path/to/payload.json|yaml` (relative to the fixtures file folder)
- **expectedStatus** (optional): either an **int** (e.g. `201`) or a coarse class (`"2xx"`, `"4xx"`, …) or a list of these.  
Example:  
```yaml
  expectedStatus: 200
  expectedStatus: 2xx
  expectedStatus: [200, 204, 404]
  expectedStatus: [2xx, 404]
```

- **save** (optional): map of `varName: $.json.path` (JsonPath) to store into the shared `vars` map
- **basedOn** (optional): **same-file** name of a base fixture; deep-merge rules below



### Group of fixtures vs single fixture

A fixture can either be
- a 'single' fixture: i.e. a HTTP call (method, endpoint, payload, …)
- a group fixture: a grouping of 'single' fixtures, in which case it declares a `fixtures:` field, which lists these 'single' fixtures to be executed in the given order.

A fixture cannot be both at once.

Hence this is invalid:
```yaml
- name: BadOne
  method: POST
  endpoint: /something
  fixtures:
    - other
```

Note: it can be a good practise to have names of groups start with a Capital letter to distinguish them apart from simple fixture.

In case of collision (several fixtures or group of fixtures with same name then the engine will fail with an error)

For now, nested group is not handled (specifically): behaviour is unspecified.


<br />



## 2) Where place the YAML files / YAML File discovery

- Default location:
```
classpath:/fixtures/{package}/{TestClassName}/fixtures.yaml
```
- `{package}`: your test’s Java package with dots → slashes  
- `{TestClassName}`: simple name (no nested class handling)

- If the file is missing, you’ll get a clear failure like:

```
[FixtureEngine] No fixtures file found for com.example.MyTest
Expected at: classpath:/integrationtests/fixtures/com/example/MyTest/fixtures.yaml
Hint: place the file under src/test/resources/integrationtests/fixtures/com/example/MyTest/fixtures.yaml
```

How the engine looks for the fixtures:
- Fixture (both direct calls and fixtures listed inside fixture groups) are resolved **hierarchically** (closest-first) up the package tree inside the resource folder: 


- Hierarchy Example:
```
src/test/resources/
└─ integrationtests/
   └─ fixtures/
      └─ com/
         └─ fixtures.yaml       
         └─ my/
            └─ pckg/
               └─ MyIntegrationTest/
                  └─ fixtures.yaml

```

This allows us to define fixtures higher up in the hierarchy that will be visible and usable by several Junit test classes.


<br />


## 3) Fixture lookup: overriding/factorizing

### 3.1) How it works

When you run a fixture by name (directly or via a local group) from test "{package }.{TestClassName}", the engine searches up the package hierarchy, from the closest fixtures.yaml next to the test class (classpath:/fixtures/{package}/{TestClassName}/fixtures.yaml if it exists) to its ancestors, until it finds the first definition; that one is used.  

If the same fixture name exists in multiple layers, the closest (lower) file completely overrides the higher one (no cross-file merge).

Group policies themselves are local only (defined in the closest file), but the fixture names listed inside a group are still resolved using the same hierarchical lookup. Unknown names fail fast with a clear error.

### 3.2) Example

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
  - name: create_order
    method: POST
    endpoint: /order
    expectedStatus: 201
```

**`.../sales/order/fixtures.yaml`**
```yaml
fixtures:
  - name: create_order   # overrides (replaces completely) the higher-level one
    method: POST
    endpoint: /order?source=xyz   
    expectedStatus: 201
```

**`.../sales/order/OrderApiIT/fixtures.yaml`**
```yaml
fixtures:
  - name: BeforeAll
    fixtures: [ create_order, create_order_specific ]

  - name: create_order_specific   # overrides (replaces completely) the higher-level ones
    method: POST
    endpoint: /order/init
    expectedStatus: 201
```

### Resolution behavior
- `fx.callFixture("create_order")` in `OrderApiIT` resolves to the **closest** definition:
  - first looks in `.../order/OrderApiIT/fixtures.yaml` (no match),
  - then `.../order/fixtures.yaml` (**match — used**),
  - stops without going up to `.../sales/fixtures.yaml`.
- Group `"BeforeAll"` runs names in order. For `create_order`, the same **closest-first** resolution applies. Unknown names → fail with the name and the file that referenced it.

<br />


## 4) `basedOn`: fine-grained local override

### 4.1) How it works

As opposed to the override mechanism described in the previous section, `basedOn` lets us define a fixture that will  inherit the contents of another (local to the same file for the time being) fixture while selectively redefining only the fields you care about. Instead of replacing the entire definition by name, you can fine-tune specific attributes, while the rest are seamlessly merged from the parent. This makes it possible to build layered, reusable fixtures that share a common structure but differ in just a few details, keeping your test data concise and consistent.

The basedOn property expects a fixture name and looks it up inside the same file. In future versions, the lookup will be extended to the package hierarchy, enabling you to reference and reuse fixtures defined in higher-level fixture files without duplicating them.

If basedOn fixture is not found → fail fast with the layer/file path in the error.

**Exception**: the `save` field is ignored by the basedOn mechanism. The one defined in the 'based on' fixture is always ignored.


### 4.2) The payload field

The payload field is handled in such a way as to allow for the selective redefinition of parts of the JSON content without having to copy the entire structure. Only the keys you explicitly provide will override the corresponding entries from the parent.

The payload provided by the fixture that bases itself on another one is therefore deep-merged with the parent’s payload: any new keys are added, matching keys are replaced, and all other keys are inherited unchanged. This makes it easy to keep fixtures concise, avoid duplication, and highlight only the differences that matter.

Deep-merge rules:
- Scalars (`method`, `endpoint`, `expectedStatus`) → child overrides if present.
- Maps (`headers`, `query`, `save`) → shallow merge, child keys override parent.
- `payload`:
  - If both parent & child are **objects** → deep-merge recursively (child wins on conflicts).
  - If either side is an **array or scalar** → child **replaces** parent entirely.
  - If child payload is a **JSON string**, it’s parsed to an object **before** merging.



### 4.3) Example

```yaml
fixtures:
  - name: base_product
    method: POST
    endpoint: /catalog/product
    expectedStatus: 201
    headers: { X-Trace: base }
    payload:
      productType: "Electronics"
      subType: "To be defined"
      details:
        new: true
        stock: 50
        tags: ["wireless","bluetooth"]

  - name: product_laptop
    basedOn: base_product               # reuse everything...
    headers: { X-Trace: child }         # ... but tweak headers
    endpoint: /catalog/product?category=Laptop  # ... and endpoints
    payload: >                          # ... and merge JSON
      {  "subType": "Laptop",
         "details":
          { "stock": 20,                # overrides
            "tags": ["touchscreen"]     # array replaces parent array
          }
      }

```

**Effective payload for `product_laptop`:**
```json
{
  "productType": "Electronics",
  "subType"    : "Laptop",
  "details": {
    "new": true,
    "stock": 20,
    "tags": ["touchscreen"]
  }
}
```

**Guidance:**
- Use **override by name** when you want a **completely different** local definition.
- Use **`basedOn`** when you want to **reuse** most of a definition and **tweak** specific parts.

<br />



## 5) Variables & templating — how to set and use

There’s a single shared `Map<String,Object>` of variables per engine (`fx.vars()`).

### 5.1) Setting variables from responses
```yaml
fixtures:
  - name: create_order
    method: POST
    endpoint: /order
    expectedStatus: 201
    payload: { contractualStep: [70], subregionId: 1, buyers: [Alice","Bob"], contracts: [] }
    save:
      order_1: $.id          # JsonPath into the response body
```

After running `fx.callFixture("create_order")`, the engine will have:
- `vars().get("order_1")` → the created ID
- convenience stashes when you use `callFixtureReturn(...)`:
  - `_last` contains the extracted value
  - top-level key from your JsonPath (e.g. `id` for `$.id`)
  - `<fixtureName>.<key>` (e.g. `create_bcs.id`)

### 5.2) Using variables in YAML (templating)
You can interpolate `{{var}}` in:
- `endpoint`
- header values
- query values
- all string leaves inside `payload` (YAML/JSON)

```yaml
fixtures:
  - name: create_item
    method: POST
    endpoint: /order/{{order_1}}/item
    expectedStatus: 201
    payload:
      title: "Item for order {{order_1}}"
      options:
        - "{{someFlag}}"        # will be replaced if set in vars

  - name: delete_item
    method: DELETE
    endpoint: /order/{{order_1}}/delete
    expectedStatus: 200
```

You can also pre-seed variables in code:
```java
fx.vars().put("someFlag", "wifi");
```

### 5.3) Getting variables in Java
```java
Object any = fx.vars().get("order_1");
long id = Long.parseLong(String.valueOf(any));

// or use convenience extractors during execution:
long itemId = fx.callFixtureReturnId("create_item");
Object title = fx.callFixtureReturn("create_item", "$.title");  // also stashes _last and keys
```

<br />


## 6) Lax mode

The FixtureEngine supports a lax execution mode for fixtures and groups, designed for best-effort cleanup and other scenarios where strictness is not desirable.

### How to call
```java
fx.callFixture("MyFixture");       // strict (default) => no lax mode
fx.callFixtureLax("MyFixture");    // lax mode
fx.callFixture("MyFixture", true); // lax (explicit overload)
```

### Key points

Lax mode for:
- single fixtures:
   - status mismatches are tolerated
   - all other errors are also tolerated
- group fixtures:
  - each child fixture is still executed (in order as per usual). Just like single fixtures:
    - status mismatches are tolerated
    - other errors are also tolerated
  - in the case of a child fixture generating an error, the error is simply logged and the fixture engine moves on to the next one


### Typical use cases

- Cleanup groups (AfterEach, AfterAll) where some delete calls may fail:
  - entity already deleted in the test body
  - access/rights considerations (e.g. UnauthorizedException: The user with id = local is not allowed to access to this resource)  

- or if you want to use one same cleanup procedure (=> one fixture group) for all your tests for convenience = best effort teardown to ensure the test environment is left clean without failing tests unnecessarily.

- Exploratory scenarios where you want to capture results even if not all fixtures succeed.

<br />





## 7) Authentication & CSRF

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

<br />




## 8) Using in tests

### 8.1) Option A: Base class (recommended)

`ParentIntegrationTest` keeps setup consistent and tiny:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles({"test"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ParentIntegrationTest 
{
  @Autowired protected MockMvc mockMvc;
  @Autowired protected MyUserRepository myUserRepository;
  protected FixtureEngine fx;

  @BeforeAll
  void bootBase() {
    fx = FixtureEngine.forTestClass(getClass())
        .mockMvc(mockMvc)
        .authentication(getTestAuthentication())
        .build();
    fx.callFixture("BeforeAll");   // and define that group fixture in fixtures.yaml
  }

  @AfterAll
  void teardownBase() 
  { fx.callFixture("AfterAll");   // and define that group fixture in fixtures.yaml
  }

   @BeforeEach
   void perTestBase() 
   {  // Example. Or don't do anything and let each test  itself handle this.
      fx.callFixture("BeforeEach");     // and define that group fixture in fixtures.yaml
   }

   @AfterEach
   void afterTestBase() 
   {  // Example. Or don't do anything and let each test  itself handle this.
      fx.callFixture("AfterEach");      // and define that group fixture in fixtures.yaml
   }

  protected Authentication getTestAuthentication() 
  { // Example
    var user = myUserRepository.findUserByUserId("local");
    return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
  }
}
```

Subclass:

```java
class MyIntegrationTest extends ParentIntegrationTest 
{

  @Test
  void createsOffers() throws Exception {
    long offer1 = fx.callFixtureReturnId("create-this");
    long offer2 = fx.callFixtureReturnId("create-that");
    long offer3 = fx.callFixtureReturnId("call-this-other-endpoint");
    // ...assertions...
  }

   // If you ever want per-test fixture groups, just override the hooks:
   @Test
   protected void shouldDoThisWhen()
   { ...
   }


}
```

### Option B: Ad-hoc usage

If you don’t want a base class:

```java
FixtureEngine fx = FixtureEngine.forTestClass(getClass())
    .mockMvc(mockMvc)
    .authentication(getTestAuthentication())
    .build();

fx.callFixture("BeforeAll");
long id = fx.callFixtureReturnId("create_order");
```

<br />



## 9) API features

- `fx.callFixture("name")` → runs a single or a group (strict).
- `fx.callFixtureLax("name")` → same, but best-effort.
- `fx.callFixture("name", true)` → explicit lax.
- `fx.callFixtureReturnId("name")` → runs a **single** fixture, returns `$.id` (long).
- `fx.callFixtureReturn("name", "$.path")` → runs a **single** fixture, extracts value and stashes `_last`.
- `fx.vars()` → shared variables map (templating uses `{{var}}`).


## 10) Cookbook

### 10.1) Minimal setup/teardown groups
**YAML**
```yaml
fixtures:
  - name: BeforeAll
    fixtures: [ create_order ]

  - name: AfterAll
    fixtures: [ delete_order ]

  - name: create_order
    method: POST
    endpoint: /order
    expectedStatus: 201
    payload: { buyer: "Alice" }
    save: { orderId: $.id }

  - name: delete_order
    method: DELETE
    endpoint: /order/{{orderId}}
    expectedStatus: [200, 204]   # allow idempotent deletes
```

**Java**
```java
@BeforeAll void bootBase() { fx.callFixture("BeforeAll"); }
@AfterAll  void teardown() { fx.callFixtureLax("AfterAll"); } // best-effort
```

---

### 10.2) Create, then extract a field (convenience extractors)
**YAML**
```yaml
fixtures:
  - name: create_item
    method: POST
    endpoint: /order/{{orderId}}/item
    expectedStatus: 201
    payload: { sku: "SKU-1", quantity: 2 }
    save: { itemId: $.id }
```

**Java**
```java
long itemId = fx.callFixtureReturnId("create_item");           // extracts $.id as long
Object sku   = fx.callFixtureReturn("create_item", "$.sku");   // extracts any path, also stashes _last
```



### 10.3) Templating with pre-seeded variables
**Java**
```java
fx.vars().put("orderId", 123L);
fx.vars().put("note", "rush-order");
fx.callFixture("patch_order");
```

**YAML**
```yaml
fixtures:
  - name: patch_order
    method: PATCH
    endpoint: /order/{{orderId}}
    expectedStatus: 200
    payload:
      note: "{{note}}"
```


### 10.4) Cargo-cult payloads: YAML object, JSON string, classpath include
**YAML**
```yaml
fixtures:
  - name: create_yaml
    method: POST
    endpoint: /catalog/product
    expectedStatus: 201
    payload:
      productType: "Electronics"
      details: { new: true, stock: 50 }

  - name: create_json_string
    method: POST
    endpoint: /catalog/product
    expectedStatus: 201
    payload: >
      { "productType": "Home", "details": { "new": false, "stock": 5 } }

  - name: create_from_file
    method: POST
    endpoint: /catalog/product
    expectedStatus: 201
    payload: classpath:payloads/product.json   # relative to fixtures file folder
```

---

### 10.5) basedOn to reuse, with deep-merge payloads (arrays replace)
**YAML**
```yaml
fixtures:
  - name: base_product
    method: POST
    endpoint: /catalog/product
    expectedStatus: 201
    headers: { X-Trace: base }
    payload:
      productType: "Electronics"
      details: { new: true, stock: 50, tags: ["wireless","bluetooth"] }

  - name: product_laptop
    basedOn: base_product
    headers: { X-Trace: child }               # map-merge (child keys replace)
    endpoint: /catalog/product?category=Laptop
    payload: >
      { "details": { "stock": 20, "tags": ["touchscreen"] } }  # array replaces
    # save is NOT inherited; define your own if you need it
```

---

### 10.6) Expect multiple acceptable statuses (2xx class or list)
**YAML**
```yaml
fixtures:
  - name: upsert_order
    method: PUT
    endpoint: /order/{{orderId}}
    expectedStatus: [200, 201]     # created or updated both ok
    payload: { buyer: "Alice" }

  - name: probe_status
    method: GET
    endpoint: /health
    expectedStatus: 2xx
```

---

### 10.7) Best-effort teardown with lax mode
**Java**
```java
@AfterEach
void afterEach() {
  fx.callFixtureLax("AfterEach");  // tolerate any errors, continue
}
```

**YAML**
```yaml
fixtures:
  - name: AfterEach
    fixtures: [ delete_item_1, delete_item_2, delete_order ]

  - name: delete_item_1
    method: DELETE
    endpoint: /order/{{orderId}}/item/{{item1Id}}
    expectedStatus: [200, 204]

  - name: delete_item_2
    basedOn: delete_item_1
    endpoint: /order/{{orderId}}/item/{{item2Id}}

  - name: delete_order
    method: DELETE
    endpoint: /order/{{orderId}}
    expectedStatus: [200, 204]
```

---

### 10.8) Save multiple values from arrays/objects (JsonPath)
**YAML**
```yaml
fixtures:
  - name: list_items
    method: GET
    endpoint: /order/{{orderId}}/item
    expectedStatus: 200
    save:
      firstSku:  $[0].sku
      lastPrice: $[-1:].price[0]   # last element's price
```

**Java**
```java
fx.callFixture("list_items");
String sku = String.valueOf(fx.vars().get("firstSku"));
String price = String.valueOf(fx.vars().get("lastPrice"));
```

---

### 10.9) Query params & headers with templating
**YAML**
```yaml
fixtures:
  - name: search_products
    method: GET
    endpoint: /catalog/search
    query:    { q: "{{needle}}", limit: "10" }
    headers:  { X-Trace: "search-{{traceId}}" }
    expectedStatus: 200
```

**Java**
```java
fx.vars().put("needle", "laptop");
fx.vars().put("traceId", "abc123");
fx.callFixture("search_products");
```

---

### 10.10) Idempotent delete: allow 404 in strict mode
**YAML**
```yaml
fixtures:
  - name: strict_delete_but_allow_404
    method: DELETE
    endpoint: /order/{{orderId}}
    expectedStatus: [200, 204, 404]   # accept not found as success
```


<br />


## 11) Troubleshooting

**401 Unauthorized on some calls**
- Ensure you pass `.authentication(...)` or `.authenticationSupplier(...)` to the builder.
- If your app requires CSRF for mutating verbs, the engine already adds it. If you still get 403/401, check your security config or required headers.

**“No fixtures file found … Expected at: classpath:/…”**
- Create the file at exactly that path under `src/test/resources/…`.

**“Fixture not found: … Available: …”**
- Name typo, or you expected a higher-level file that doesn’t declare that fixture. Remember: closest definition wins; search climbs up.

**basedOn doesn’t override payload as expected**
- If the child payload is a **JSON string**, it is **parsed first** and then merged (deep object merge; arrays replace). That’s the intended behavior.

**Caused by: java.net.URISyntaxException: Illegal character in path at index 23: /businessContractSheet/{{bcsId_1}}/delete**
- If a previous fixture call fails and then bcsId_1 variable does not get populated, the placeholder won't get remplaced and that kind of error might spring up.


<br />



## 12) Logging & diagnostics

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

<br />