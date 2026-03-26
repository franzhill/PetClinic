# Moxter Roadmap (2026)

## 🚨 Priority 1: Adoption, Infrastructure & Schema Alignment
**Goal:** Remove "magic" friction, stabilize the orchestration layer, and align the model with the long-term vision[cite: 3].
**Estimated Time:** 1 Week (approx. 40-50 hours)[cite: 4].

### Features & Infrastructure
* **Explicit Loading & `include:` System:** Shift from discovery to declaration[cite: 5, 6]. Move away from hierarchical package walking to avoid "black box" behavior[cite: 6]. Implement a formal inclusion system for explicit dependency imports[cite: 7].
* **Model/Schema Upgrade (Blueprint Conformance):** Refactor the Moxture model to move execution details under a dedicated `request:` block[cite: 8, 9]. Migrate `save:` into a robust `extract:` section supporting body, headers, and cookies, with a `strict:` toggle for JsonPath safety[cite: 10].
* **Lazy Validation & Just-in-Time Safety:** Log syntax errors and `[SHADOWING]` warnings at boot without crashing the suite[cite: 11, 12]. Throw a fatal `IllegalStateException` only if the test actually executes a broken moxture[cite: 13].
* **Orchestration & NPE Prevention:** Refactor `callInternal` to ensure it never returns null[cite: 14]. Return "Skeleton" results for groups and `allowFailure` calls to protect the fluent assertion API[cite: 15].
* **"Big Red Box" Syntax Reporting:** Capture Jackson exceptions to report exact file, line, and column for YAML errors[cite: 16].
* **Log Sanitization:** Downgrade ASCII banners to `DEBUG` and provide a `silent(true)` toggle in the builder for clean CI/CD runs[cite: 17].

---

## 🟡 Priority 2: Scenario Orchestration (YAML-Centric Feel)
**Goal:** Transform groups into full "Scenarios" that handle orchestration and cleanup within YAML[cite: 18, 19].
**Estimated Time:** 2 Weeks[cite: 20].

### Scenarios & Logic
* **The `steps:` Logic:** Refactor Group Moxtures to support a sequential list of actions[cite: 21]:
    * `call`: Execute a single moxture or another group[cite: 21].
    * `vars`: Perform variable promotion, renaming, or "piping"[cite: 22].
    * `sleep`: Native async support (e.g., `sleep: 500ms`)[cite: 23].
    * `java::`: High-extensibility bridge to call static Java methods for seeding or custom assertions[cite: 24].
* **The `finally:` Block:** Implement a cleanup section that executes regardless of success or failure to ensure environment stability[cite: 25].
* **Accumulator Variables:** Support `list[] : $.id` syntax to build arrays, including `${list[last]}` access and `list: []` resets[cite: 26].
* **Recursive Interpolation:** Allow nested logic like `${mx.func(${p.id})}`[cite: 27].
* **Polymorphic Duck Typing:** Automatically distinguish between single moxtures and groups by detecting `steps` vs. `request/endpoint`[cite: 28].
* **User Management:** Formalize user management and variable sections in the YAML file[cite: 29, 30].

---

## 🔵 Priority 3: Visibility & Reporting
**Goal:** Provide enterprise-grade transparency into test health and variable states[cite: 31, 32].
**Estimated Time:** 1 Week[cite: 33].

### Reporting & Diagnostics
* **The Startup Lineage:** Print a summary at boot showing loaded files and highlighted shadowed moxtures[cite: 34].
* **Assertion Intent (`.as()`):** Support `.as("description")` in the fluent API to show business meaning in failure reports[cite: 35].
* **Failure Diagnostics:** Ensure `MoxDiagnostics` captures Intent (resolved spec) vs. Reality (wire response) and full Variable Context[cite: 36].
* **Final Execution Summary:** Terminal report showing Passed, Failed, and Warnings[cite: 37].
* **Surgical Overrides:** Add `.override("$.path", value)` to the Java API for one-off modifications without changing YAML[cite: 38].

---

## ☑ Priority 4: Strategic Ecosystem & Tooling
**Goal:** Professionalize the ecosystem with IDE support and standalone execution[cite: 39, 40].
**Estimated Time:** 4 to 6 Weeks[cite: 41].

### Ecosystem Development
* **IDE Plugins (VSCode & IntelliJ):** Provide "Go to Definition" (Ctrl+Click) for references and real-time schema validation via `moxter-schema.json`[cite: 42, 43, 44].
* **The `play:` Section & Maven Plugin:** Support the `play:` block for standalone execution via `mvn moxter:run -Dfile=tests.yaml`[cite: 45].
* **Abstract HTTP Engine:** Decouple from `MockMvc` to allow running against live AWS/Staging environments[cite: 46].
* **Test Generator (Sniffer):** A proxy tool to "sniff" traffic and auto-generate YAML moxture files[cite: 47].