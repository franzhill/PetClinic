/* FULL FILE OMITTED FOR BREVITY IN THIS MESSAGE.
 * The code is the same as the last drop-in you pasted from me,
 * with the following concrete edits:
 *
 * 1) Add the lax overload to HttpExecutor:
 *      Model.ResponseEnvelope execute(Model.FixtureCall spec, String baseDir, Map<String,Object> vars) {
 *          return execute(spec, baseDir, vars, false);
 *      }
 *      Model.ResponseEnvelope execute(Model.FixtureCall spec, String baseDir, Map<String,Object> vars, boolean lax) { ... }
 *    Inside, when expected status mismatches:
 *      if (lax) { log.warn("(lax) ..."); } else { throw new AssertionError(...); }
 *
 * 2) Change runGroupFixture signature and calls:
 *      private void runGroupFixture(String label, List<String> list, String baseDir, boolean lax) { ... }
 *    It resolves each child fixture name and executes with:
 *      executor.execute(item.call, item.baseDir, vars, lax);
 *
 * 3) callFixture(String name) – strict:
 *      if (isGroupFixture(...)) { runGroupFixture(..., false); return null; }
 *      else return executor.execute(..., false);
 *
 * 4) Add callFixtureLax(String name) – lax:
 *      if (isGroupFixture(...)) { runGroupFixture(..., true); return null; }
 *      else return executor.execute(..., true);
 *
 * 5) callFixtureReturn* unchanged except they check and refuse group fixtures.
 */

/* --------- START OF ACTUAL DROP-IN (full file) --------- */

package com.fhi.pet_clinic.tools.fixture_engine;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import lombok.extern.slf4j.Slf4j;

/**
 * # FixtureEngine (single-file, modularized with nested classes)
 *
 * Copy-paste friendly. Public façade + helpers below.
 *
 * ## Fixtures file discovery
 * The engine loads a single file at:
 *   `classpath:/fixtures/{package}/{TestClassName}/fixtures.yaml`
 * where `{package}` is the test class's package path (dots → slashes) and `{TestClassName}`
 * is the test class simple name (no nested-class special handling).
 *
 * ## File shape (YAML)
 *
 * fixtures:
 *   - name: create_bcs
 *     method: POST
 *     endpoint: /businessContractSheet
 *     expectedStatus: 201                // int | "2xx"/"3xx"/"4xx"/"5xx" | [200,204,"2xx"]
 *     payload: {"contracts":[]}
 *     save: { bcsId_1: $.id }
 *
 *   // Group-as-fixture (NEW): a fixture that has a 'fixtures:' list is considered a group.
 *   // It **must not** mix HTTP fields with 'fixtures:' — mixing → hard error.
 *   - name: BeforeAll
 *     fixtures: [ common.create_bcs, create_bcs ]
 *
 * ## Features
 * - Single/group execution via {@link #callFixture(String)}:
 *   - A fixture row that defines 'fixtures:' is a "group fixture" (executed as a group; returns null).
 *   - Otherwise, the row is executed as a single HTTP fixture (returns ResponseEnvelope).
 *   - Hierarchical lookup (closest → parents).
 *   - Unknown names → fail fast.
 * - Payloads: YAML objects/arrays, inline JSON, raw JSON strings, or `classpath:` external files
 *   (resolved relative to the defining file's directory).
 * - Flexible expectedStatus: int | "2xx"/"3xx"/"4xx"/"5xx" | array of any of those (optional).
 * - Vars: shared map. Values saved via JsonPath; can be overwritten.
 * - Convenience: {@link #callFixtureReturn(String, String)} and {@link #callFixtureReturnId(String)} (single fixtures only).
 * - Logging: concise start/finish lines; DEBUG request & response previews; compact failure logs (no cURL repro).
 *
 * ## basedOn (within a single file)
 * - Parent must exist in the same file; fail fast otherwise.
 * - Scalars (method/endpoint/expectedStatus): child overrides if present.
 * - headers/query: map-merge (child keys override).
 * - save: REPLACE (child.save replaces parent.save; no merge).
 * - fixtures (group lists): REPLACE.
 * - payload: deep-merge objects; arrays/scalars replace. Textual JSON is parsed before merging.
 */
@Slf4j
public final class FixtureEngine 
{
    // =====================================================================
    // Top-level config (easy to tweak)
    // =====================================================================

    /** Classpath root under which fixtures live (no leading/trailing slash). */
    public static final String DEFAULT_FIXTURES_ROOT_PATH = "fixtures";

    /** If true, look in a subfolder named after the test class simple name. */
    public static final boolean DEFAULT_USE_PER_TESTCLASS_DIRECTORY = true;

    /** Single accepted file name (with extension). */
    public static final String DEFAULT_FIXTURES_BASENAME = "fixtures.yaml";



    // =====================================================================
    // Public API
    // =====================================================================

    public static Builder forTestClass(Class<?> testClass) 
    { return new Builder(testClass); 
    }


    /**
     * Execute a fixture or a group by name — hierarchical lookup (closest → parents).
     *
     * If the name resolves to a group (fixture row with 'fixtures:'), executes the whole group
     * (strict) and returns {@code null}. Otherwise executes a single HTTP fixture (strict)
     * and returns the response envelope.
     * 
     * Lax mode is off: call fails if the return status is not as expected.
     */
    public Model.ResponseEnvelope callFixture(String name) 
    {   return callFixture(name, false);
    }


    /**
     * Conveniance for a lax call.
     */
    public Model.ResponseEnvelope callFixtureLax(String name) 
    {   return callFixture(name, true);
    }


    /**
     * Execute a fixture or a group by name.
     *
     * @param name the fixture (or group) name
     * @param lax  if true, expected-status mismatches are logged (warning) and won't fail the test.
     *             For group fixtures, each child is executed in lax mode.
     * @return {@code null} for group fixtures; for single fixtures, the response envelope.
     */
    public Model.ResponseEnvelope callFixture(String name, boolean lax) 
    {
        Objects.requireNonNull(name, "name");
        Resolved r = resolveByName(name);

        if (isGroupFixture(r.call)) {
            validateGroupVsHttp(r.call);
            final String label = "group '" + name + "'" + (lax ? " (lax)" : "");
            runGroupFixture(label, r.call.fixtures(), r.baseDir, lax);
            return null;
        }

        // Single fixture
        validateGroupVsHttp(r.call);
        try {
            return executor.execute(r.call, r.baseDir, vars, lax);
        } catch (Throwable t) {
            if (lax) {
                // Best-effort: tolerate ANY error (infra, parsing, etc.)
                log.warn("[FixtureEngine] (lax) single fixture '{}' failed — skipping. Cause: {}", name, t.toString());
                return null; // skip in lax mode
            }
            // strict: rethrow
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new RuntimeException("Error executing fixture '" + name + "'", t);
        }
    }



    /** 
     * Convenience: executes a single fixture and return `$.id` as long.
     */
    public long callFixtureReturnId(String callName) 
    {
        Object v;
        try { v = callFixtureReturn(callName, "$.id"); }
        catch (Exception e) { throw new RuntimeException("Failed to extract '$.id' for fixture '" + callName + "'", e); }
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong((String) v); }
            catch (NumberFormatException ex) { throw new IllegalStateException("Value at '$.id' is not a number: " + v, ex); }
        }
        throw new IllegalStateException("Value at '$.id' is not numeric: " + v);
    }


    /**
     * Convenience: executes a single fixture and extract a value using a JsonPath.
     * 
     * Side effects: stores into vars under `_last` and `<call>.<field>` when path is `$.field`.
     * 
     * @return extracted value (Number, String, Boolean, List, Map, …)
     */
    public Object callFixtureReturn(String callName, String jsonPath) throws Exception 
    {
        Objects.requireNonNull(callName, "callName");
        Objects.requireNonNull(jsonPath, "jsonPath");

        // Guard against being called on a group
        Resolved r = resolveByName(callName);
        if (isGroupFixture(r.call)) {
            throw new IllegalArgumentException("callFixtureReturn* cannot be used on group fixture '" + callName + "'");
        }

        Model.ResponseEnvelope env = executor.execute(r.call, r.baseDir, vars, false);
        String raw = env.raw();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Fixture '" + callName + "' returned an empty body; cannot read: " + jsonPath);
        }
        Object value = JsonPath.parse(raw).read(jsonPath);
        if (log.isDebugEnabled()) log.debug("Extracted {} from '{}': {}", jsonPath, callName, Util.Logging.previewValue(value));

        vars.put("_last", value);
        String inferred = inferTopLevelKey(jsonPath);
        if (inferred != null && !inferred.isBlank()) {
            vars.put(inferred, value);
            vars.put(callName + "." + inferred, value);
        } else {
            vars.put(callName + ".value", value);
        }
        return value;
    }


    /** Access the shared variables map (templating & saves use this). */
    public Map<String,Object> vars() { return vars; }


    /** Clear all variables. */
    public void clearVars() { vars.clear(); }


    // =====================================================================
    //   Builder / construction
    // =====================================================================

    private final Class<?> testClass;
    private final Model.FixtureSuite suite;
    private final Map<String, Model.FixtureCall> byName;
    private final Map<String,Object> vars = new LinkedHashMap<>();
    private final Runtime.HttpExecutor executor;
    private final Engine.GroupRunner groups;
    private final String fixturesBaseDir;

    // For hierarchical lookup
    private final IO.ClasspathFixtureRepository repo;
    private final Engine.FixtureConfig cfg;
    private final ObjectMapper yamlMapper;

    // Keep mapper for HTTP
    private final ObjectMapper jsonMapper;

    // Auth supplier (fixed or lazy) from builder
    private final java.util.function.Supplier<org.springframework.security.core.Authentication> builderAuthSupplier;


    private FixtureEngine(Class<?> testClass,
                          MockMvc mockMvc,
                          java.util.function.Supplier<org.springframework.security.core.Authentication> authSupplier) 
    {
        this.testClass = Objects.requireNonNull(testClass, "testClass");
        Objects.requireNonNull(mockMvc, "mockMvc");
        this.builderAuthSupplier = authSupplier;

        // Build minimal config internally
        Engine.FixtureConfig cfg = new Engine.FixtureConfig(
                DEFAULT_FIXTURES_ROOT_PATH,
                DEFAULT_USE_PER_TESTCLASS_DIRECTORY,
                DEFAULT_FIXTURES_BASENAME
        );
        this.cfg = cfg;

        // YAML for reading fixtures/includes; JSON for HTTP I/O
        ObjectMapper yamlMapper = defaultYamlMapper();
        ObjectMapper jsonMapper = defaultJsonMapper();
        this.yamlMapper = yamlMapper;
        this.jsonMapper = jsonMapper;

        // Load closest fixtures file (read with YAML)
        IO.ClasspathFixtureRepository repo = new IO.ClasspathFixtureRepository(yamlMapper);
        IO.FixtureRepository.LoadedSuite loaded = repo.loadFor(testClass, cfg);

        // Materialize basedOn in the closest file
        this.suite = materializeBasedOn(yamlMapper, loaded.suite);
        this.fixturesBaseDir = loaded.baseDir;
        this.repo = repo;

        // Index fixtures by name (fail fast on duplicates) and validate structure
        Map<String, Model.FixtureCall> index = new LinkedHashMap<>();
        List<Model.FixtureCall> calls = (suite.fixtures() == null) ? Collections.emptyList() : suite.fixtures();
        for (Model.FixtureCall f : calls) {
            if (f.name() == null || f.name().isBlank()) {
                throw new IllegalStateException("Fixture with missing/blank 'name' in " + fixturesBaseDir + "/" + DEFAULT_FIXTURES_BASENAME);
            }
            validateGroupVsHttp(f);
            if (index.put(f.name(), f) != null) {
                // Fail on name collision
                throw new IllegalStateException("Duplicate fixture name: " + f.name());
            }
        }
        this.byName = Collections.unmodifiableMap(index);

        // Runtime helpers & wiring
        Runtime.StatusMatcher matcher = new Runtime.StatusMatcher();
        Runtime.Templating templating = new Runtime.SimpleTemplating();
        Runtime.PayloadResolver payloadResolver = new Runtime.PayloadResolver(yamlMapper);

        // Use JSON mapper for request/response bodies
        this.executor = new Runtime.HttpExecutor(mockMvc, jsonMapper, templating, payloadResolver, matcher, builderAuthSupplier);
        this.groups = new Engine.GroupRunner(item -> executor.execute(item.call, item.baseDir, vars, false));
    }

    public static final class Builder 
    {
        private final Class<?> testClass;
        private MockMvc mockMvc;
        private java.util.function.Supplier<org.springframework.security.core.Authentication> authSupplier;

        private Builder(Class<?> testClass) { this.testClass = testClass; }
        public Builder mockMvc(MockMvc mvc) { this.mockMvc = mvc; return this; }

        /** Provide a fixed Authentication to attach to every request. */
        public Builder authentication(org.springframework.security.core.Authentication auth) {
            this.authSupplier = () -> auth;
            return this;
        }

        /** Provide a lazy supplier for Authentication (called per request). */
        public Builder authenticationSupplier(java.util.function.Supplier<org.springframework.security.core.Authentication> s) {
            this.authSupplier = s;
            return this;
        }

        public FixtureEngine build() {
            return new FixtureEngine(testClass, mockMvc, authSupplier);
        }
    }

    // =====================================================================
    //   Private helpers
    // =====================================================================

    /** Resolve a name to a concrete fixture (closest → parents), with its baseDir. */
    private Resolved resolveByName(String name) 
    {
        // 1) Try closest file first
        Model.FixtureCall local = byName.get(name);
        if (local != null) {
            return new Resolved(local, fixturesBaseDir);
        }
        // 2) Hierarchical lookup upwards (closest → ... → root)
        IO.ClasspathFixtureRepository.Resolved found = repo.findFirstByName(testClass, cfg, name, yamlMapper);
        if (found == null) {
            List<String> attempted = repo.candidateAncestorPaths(testClass, cfg);
            throw new IllegalArgumentException("Fixture/Group not found by name: " + name
                    + ". Looked under: " + attempted);
        }
        return new Resolved(found.call, found.baseDir);
    }

    private static final class Resolved {
        final Model.FixtureCall call;
        final String baseDir;
        Resolved(Model.FixtureCall call, String baseDir) { this.call = call; this.baseDir = baseDir; }
    }


    private static String inferTopLevelKey(String jsonPath) {
        String p = jsonPath.trim();
        if (p.startsWith("$.") && p.length() > 2) {
            int end = p.indexOf('[', 2);
            return (end > 0) ? p.substring(2, end) : p.substring(2);
        }
        if (p.startsWith("$['")) {
            int end = p.indexOf("']", 3);
            if (end > 3) return p.substring(3, end);
        }
        return null;
    }

    private static ObjectMapper defaultYamlMapper() {
        return new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    private static ObjectMapper defaultJsonMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    // =====================================================================
    //   Nested modules (config/engine/io/runtime/util/model)
    // =====================================================================

    /** Engine-related helpers. */
    static final class Engine 
    {

        /** Immutable configuration used for locating fixtures on classpath. */
        static final class FixtureConfig {
            final String rootPath;                // e.g., "integrationtests2/fixtures"
            final boolean perTestClassDirectory;  // true => add "/{TestClassName}"
            final String fileName;                // "fixtures.yaml" (includes extension)

            FixtureConfig(String rootPath, boolean perTestClassDirectory, String fileName) {
                this.rootPath = trim(rootPath);
                this.perTestClassDirectory = perTestClassDirectory;
                this.fileName = trim(fileName);
            }
            private static String trim(String s) {
                if (s == null) return "";
                String out = s.trim();
                while (out.startsWith("/")) out = out.substring(1);
                while (out.endsWith("/")) out = out.substring(0, out.length()-1);
                return out;
            }
        }

        /** Item scheduled for execution: the resolved call + its defining file's baseDir. */
        static final class PlanItem {
            final Model.FixtureCall call;
            final String baseDir;
            PlanItem(Model.FixtureCall call, String baseDir) { this.call = call; this.baseDir = baseDir; }
            String name() { return call.name(); }
        }

        /** Runs a list of fixtures with ordering + human logs (explicit order). */
        @Slf4j
        static final class GroupRunner {
            interface Exec { Model.ResponseEnvelope run(PlanItem item); }
            private final Exec exec;
            GroupRunner(Exec exec) { this.exec = exec; }

            void run(String groupLabel, List<PlanItem> list) {
                if (list.isEmpty()) {
                    log.info("[FixtureEngine] No fixtures found for group [{}].", groupLabel); return;
                }
                log.debug("[FixtureEngine] Executing group [{}, size: {}, names: {}]", groupLabel, list.size(), names(list));
                for (PlanItem it : list) {
                    exec.run(it);
                }
            }

            private static String names(List<PlanItem> list) {
                List<String> names = new ArrayList<>(list.size());
                for (PlanItem it : list) names.add(it.name());
                return names.toString();
            }
        }
    }

    /** Loading fixtures from classpath. */
    static final class IO {

        interface FixtureRepository {
            final class LoadedSuite {
                public final Model.FixtureSuite suite;
                public final String baseDir; // classpath folder where the fixtures file lives
                public LoadedSuite(Model.FixtureSuite suite, String baseDir) { this.suite = suite; this.baseDir = baseDir; }
            }
            LoadedSuite loadFor(Class<?> testClass, Engine.FixtureConfig cfg);
        }

        /**
         * Classpath repository:
         * - Build exact closest path: rootPath + "/" + {package as folders} + ["/{TestClassName}"] + "/" + fileName
         * - Try TCCL, fall back to test class CL, then this class CL.
         * - If not found, throw with a clear message.
         * - Provides hierarchical name lookup helpers.
         */
        static final class ClasspathFixtureRepository implements FixtureRepository {
            private final ObjectMapper mapper;

            ClasspathFixtureRepository(ObjectMapper mapper) { this.mapper = mapper; }

            public LoadedSuite loadFor(Class<?> testClass, Engine.FixtureConfig cfg) {
                final String classpath = buildClosestClasspath(testClass, cfg);
                final String displayPath = "classpath:/" + classpath;

                URL url = firstNonNullUrl(
                        Thread.currentThread().getContextClassLoader(),
                        testClass.getClassLoader(),
                        FixtureEngine.class.getClassLoader(),
                        classpath
                );

                if (url == null) {
                    throw new IllegalStateException(
                        "[FixtureEngine] No fixtures file found for " + testClass.getName() + "\n" +
                        "Expected at: " + displayPath + "\n" +
                        "Hint: place the file under src/test/resources/" + classpath
                    );
                }

                if (isDebug()) System.out.println("[FixtureEngine] Loading " + displayPath + " -> " + url);

                try (InputStream in = url.openStream()) {
                    Model.FixtureSuite suite = mapper.readValue(in, Model.FixtureSuite.class); // YAML mapper parses JSON too
                    String baseDir = parentDirOf(classpath);
                    return new LoadedSuite(suite, baseDir);
                } catch (IOException e) {
                    throw new RuntimeException("Failed loading fixtures: " + displayPath, e);
                }
            }

            /* ===== Hierarchical lookup (helpers) ===== */

            /** Returns candidate ancestor classpaths from closest → root (inclusive). */
            List<String> candidateAncestorPaths(Class<?> testClass, Engine.FixtureConfig cfg) {
                List<String> out = new ArrayList<>();
                String pkg = (testClass.getPackageName() == null ? "" : testClass.getPackageName().replace('.', '/'));

                // 1) If per-class dir is enabled, add the closest file first
                if (cfg.perTestClassDirectory) {
                    out.add(cfg.rootPath + (pkg.isEmpty() ? "" : "/" + pkg) + "/" + testClass.getSimpleName() + "/" + cfg.fileName);
                }

                // 2) Then each package ancestor level: root/pkg/.../fixtures.yaml → ... → root/fixtures.yaml
                if (!pkg.isEmpty()) {
                    String[] parts = pkg.split("/");
                    for (int i = parts.length; i >= 1; i--) {
                        String prefix = String.join("/", java.util.Arrays.copyOf(parts, i));
                        out.add(cfg.rootPath + "/" + prefix + "/" + cfg.fileName);
                    }
                }

                // 3) Finally, the absolute root under cfg.rootPath
                out.add(cfg.rootPath + "/" + cfg.fileName);

                return out;
            }

            /** Finds the first (closest) occurrence of a fixture by name across ancestor files. */
            Resolved findFirstByName(Class<?> testClass, Engine.FixtureConfig cfg, String name, ObjectMapper yamlMapper) {
                List<String> candidates = candidateAncestorPaths(testClass, cfg);
                ClassLoader tccl = Thread.currentThread().getContextClassLoader();
                ClassLoader fallback = FixtureEngine.class.getClassLoader();
                for (String cp : candidates) {
                    URL url = firstNonNullUrl(tccl, testClass.getClassLoader(), fallback, cp);
                    if (url == null) continue;
                    try (InputStream in = url.openStream()) {
                        Model.FixtureSuite raw = yamlMapper.readValue(in, Model.FixtureSuite.class);
                        if (raw.fixtures() == null || raw.fixtures().isEmpty()) continue;

                        // materialize basedOn in that file before searching
                        Model.FixtureSuite suite = materializeBasedOn(yamlMapper, raw);

                        for (Model.FixtureCall f : suite.fixtures()) {
                            if (name.equals(f.name())) {
                                String baseDir = parentDirOf(cp);
                                return new Resolved(f, baseDir, "classpath:/" + cp);
                            }
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("Failed reading " + cp, e);
                    }
                }
                return null;
            }

            static final class Resolved {
                final Model.FixtureCall call;
                final String baseDir;
                final String displayPath;
                Resolved(Model.FixtureCall call, String baseDir, String displayPath) {
                    this.call = call; this.baseDir = baseDir; this.displayPath = displayPath;
                }
            }

            /* ===== internals ===== */

            private static URL firstNonNullUrl(ClassLoader tccl, ClassLoader testCl, ClassLoader fallback, String path) {
                URL u = (tccl != null ? tccl.getResource(path) : null);
                if (u != null) return u;
                u = (testCl != null ? testCl.getResource(path) : null);
                if (u != null) return u;
                return (fallback != null ? fallback.getResource(path) : null);
            }

            private static String buildClosestClasspath(Class<?> testClass, Engine.FixtureConfig cfg) {
                String pkg = (testClass.getPackageName() == null ? "" : testClass.getPackageName().replace('.', '/'));
                String classDir = cfg.perTestClassDirectory ? ("/" + testClass.getSimpleName()) : "";
                String base = (pkg.isEmpty() ? cfg.rootPath : (cfg.rootPath + "/" + pkg)) + classDir + "/";
                String full = base + cfg.fileName; // EXACT file name (e.g., "fixtures.yaml")
                if (isDebug()) System.out.println("[FixtureEngine] Expecting fixtures at: classpath:/" + full + " for " + testClass.getName());
                return full;
            }

            private static String parentDirOf(String path) {
                int i = path.lastIndexOf('/');
                return (i > 0) ? path.substring(0, i) : "";
            }
            private static boolean isDebug() {
                return "true".equalsIgnoreCase(System.getProperty("fixtureengine.debug", "false"));
            }
        }
    }

    /** Runtime: templating, payloads, status matching, http execution. */
    static final class Runtime {

        interface Templating {
            String apply(String s, Map<String,Object> vars);
            Map<String,String> applyMapValuesOnly(Map<String,String> in, Map<String,Object> vars);
        }

        /** Simple {{var}} string substitution. */
        static final class SimpleTemplating implements Templating {
            public String apply(String s, Map<String,Object> vars) {
                if (s == null || vars == null || vars.isEmpty()) return s;
                String out = s;
                for (Map.Entry<String,Object> e : vars.entrySet()) {
                    out = out.replace("{{" + e.getKey() + "}}", String.valueOf(e.getValue()));
                }
                return out;
            }
            /** Only applies templating to values (keeps keys untouched). */
            public Map<String,String> applyMapValuesOnly(Map<String,String> in, Map<String,Object> vars) {
                if (in == null || in.isEmpty()) return in;
                Map<String,String> out = new LinkedHashMap<>(in.size());
                for (Map.Entry<String,String> e : in.entrySet()) out.put(e.getKey(), apply(e.getValue(), vars));
                return out;
            }
        }

        static final class PayloadResolver {
            private final ObjectMapper mapper;
            PayloadResolver(ObjectMapper mapper) { this.mapper = mapper; }

            JsonNode resolve(JsonNode payload, String baseDir, Map<String,Object> vars, Templating tpl) throws IOException {
                if (payload == null) return null;

                if (payload.isTextual()) {
                    String txt = tpl.apply(payload.asText().trim(), vars);
                    String lower = txt.toLowerCase(Locale.ROOT);

                    // classpath: → load external file
                    if (lower.startsWith("classpath:")) {
                        String rawPath = txt.substring(txt.indexOf(':') + 1).trim();
                        return loadClasspathPayload(baseDir, rawPath);
                    }

                    // Raw JSON string?
                    if (looksLikeJson(txt)) return mapper.readTree(txt);

                    // Plain string → keep as JSON text node
                    return mapper.getNodeFactory().textNode(txt);
                }

                // YAML object/array: apply templating on string leaves
                return templateNodeStrings(payload, vars, tpl);
            }

            private JsonNode loadClasspathPayload(String baseDir, String rawPath) throws IOException {
                // Relative to the fixtures file directory unless absolute "/..."
                String path = rawPath.startsWith("/") ? rawPath.substring(1) : (baseDir + "/" + rawPath);

                URL url = null;
                ClassLoader tccl = Thread.currentThread().getContextClassLoader();
                if (tccl != null) url = tccl.getResource(path);
                if (url == null) {
                    ClassLoader fallback = FixtureEngine.class.getClassLoader();
                    if (fallback != null) url = fallback.getResource(path);
                }

                if (url == null) throw new IllegalArgumentException("Resource not found on classpath: " + rawPath + " (resolved: " + path + ")");

                try (InputStream in = url.openStream()) {
                    return mapper.readTree(in); // YAML mapper parses YAML or JSON
                }
            }

            private static boolean looksLikeJson(String s) {
                if (s == null || s.isEmpty()) return false;
                char first = s.charAt(0), last = s.charAt(s.length() - 1);
                return (first == '{' && last == '}') || (first == '[' && last == ']');
            }

            /** Recursively apply templating to all string leaves of a JSON/YAML node. */
            private JsonNode templateNodeStrings(JsonNode node, Map<String,Object> vars, Templating tpl) {
                if (node == null) return null;
                if (node.isTextual()) {
                    String replaced = tpl.apply(node.asText(), vars);
                    return mapper.getNodeFactory().textNode(replaced);
                }
                if (node.isArray()) {
                    ArrayNode arr = mapper.getNodeFactory().arrayNode();
                    for (JsonNode child : node) arr.add(templateNodeStrings(child, vars, tpl));
                    return arr;
                }
                if (node.isObject()) {
                    ObjectNode out = mapper.createObjectNode();
                    Iterator<String> it = node.fieldNames();
                    while (it.hasNext()) {
                        String f = it.next();
                        out.set(f, templateNodeStrings(node.get(f), vars, tpl));
                    }
                    return out;
                }
                return node;
            }
        }

        static final class StatusMatcher {
            /**
             * expected: null | int | "2xx"/"3xx"/"4xx"/"5xx" | "201" | [ ... any of those ... ]
             */
            boolean matches(JsonNode expected, int actual) {
                if (expected == null || expected.isNull()) return true; // optional

                // allow arrays → any element matching is accepted
                if (expected.isArray()) {
                    for (JsonNode e : expected) {
                        if (matches(e, actual)) {
                            return true;
                        }
                    }
                    return false;
                }

                if (expected.isInt()) return expected.asInt() == actual;
                if (expected.isTextual()) {
                    String s = expected.asText().trim();
                    if (s.matches("[1-5]xx")) {
                        int base = (s.charAt(0) - '0') * 100;
                        return actual >= base && actual < base + 100;
                    }
                    try { return Integer.parseInt(s) == actual; }
                    catch (NumberFormatException ignored) { return false; }
                }
                return false;
            }
        }

        @Slf4j
        static final class HttpExecutor {
            private final MockMvc mockMvc;
            private final ObjectMapper jsonMapper; // to send the payload as JSON
            private final Templating tpl;
            private final PayloadResolver payloads;
            private final StatusMatcher statusMatcher;
            private final java.util.function.Supplier<org.springframework.security.core.Authentication> authSupplier;

            HttpExecutor(MockMvc mockMvc,
                         ObjectMapper jsonMapper,
                         Templating tpl,
                         PayloadResolver payloads,
                         StatusMatcher statusMatcher,
                         java.util.function.Supplier<org.springframework.security.core.Authentication> authSupplier) {
                this.mockMvc = mockMvc;
                this.jsonMapper = jsonMapper;
                this.tpl = tpl;
                this.payloads = payloads;
                this.statusMatcher = statusMatcher;
                this.authSupplier = authSupplier;
            }

            /** Strict convenience. */
            Model.ResponseEnvelope execute(Model.FixtureCall spec, String baseDir, Map<String,Object> vars) {
                return execute(spec, baseDir, vars, false);
            }

            /**
             * Execute a single fixture call.
             * Logs a concise start line, rich DEBUG details, response preview, a finish line with duration,
             * and a compact warning when the expected status does not match.
             *
             * @param spec
             * @param baseDir
             * @param vars
             * @param lax do not fail on expected-status mismatches (still throws for infra errors)
             */
            Model.ResponseEnvelope execute(Model.FixtureCall spec, String baseDir, Map<String,Object> vars, boolean lax) 
            {
                log.debug("FHI lax = {}", lax);
                final long t0 = System.nanoTime();
                final String name   = (spec.name() == null || spec.name().isBlank()) ? "<unnamed>" : spec.name();
                final String method = safeMethod(spec.method());

                try {
                    // 1) Resolve endpoint, headers, query with templating (values only for maps)
                    final String             endpoint = tpl.apply(spec.endpoint(), vars);
                    final Map<String,String> headers0 = tpl.applyMapValuesOnly(spec.headers(), vars);
                    final Map<String,String> query    = tpl.applyMapValuesOnly(spec.query(), vars);
                    final URI                uri      = URI.create(appendQuery(endpoint, query));

                    // 2) Resolve payload (YAML/JSON node or text with classpath: include)
                    final JsonNode payloadNode = payloads.resolve(spec.payload(), baseDir, vars, tpl);

                    // 3) Human-friendly start + DEBUG details
                    log.info("[FixtureEngine] >>> Executing fixture:  [{}, {}, {}]", name, method, uri);
                    if (log.isDebugEnabled()) {
                        log.debug("[FixtureEngine] more info: expected={} headers={} query={} vars={} payload={}",
                                expectedStatusPreview(spec.expectedStatus()),
                                Util.Logging.previewHeaders(headers0),
                                (query == null || query.isEmpty() ? "{}" : query.toString()),
                                Util.Logging.previewVars(vars),
                                Util.Logging.previewNode(payloadNode));
                    }

                    // 4) Build request
                    MockHttpServletRequestBuilder req = toRequestBuilder(method, uri);
                    Map<String,String> headers = (headers0 == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(headers0);

                    // 4.1) Attach Authentication from supplier/fallback + CSRF
                    org.springframework.security.core.Authentication auth = null;
                    if (authSupplier != null) {
                        try { auth = authSupplier.get(); } catch (Exception ignore) {}
                    }
                    if (auth == null) {
                        auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                    }
                    if (auth != null) {
                        req.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth));
                        if (log.isDebugEnabled()) log.debug("[FixtureEngine] using Authentication principal={}", safeName(auth));
                    }
                    if (requiresCsrf(method)) {
                        req.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf());
                        if (log.isDebugEnabled()) log.debug("[FixtureEngine] CSRF token added for {}", method);
                    }

                    if (!headers.isEmpty()) for (Map.Entry<String,String> e : headers.entrySet()) req.header(e.getKey(), e.getValue());
                    if (payloadNode != null) {
                        req.content(jsonMapper.writeValueAsBytes(payloadNode));
                        if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
                            req.contentType(MediaType.APPLICATION_JSON);
                        }
                    }

                    // === 5) Execute + guarded parsing ===
                    MockHttpServletResponse mvcResp = mockMvc.perform(req).andReturn().getResponse();

                    final String raw = mvcResp.getContentAsString(StandardCharsets.UTF_8);
                    final String ctHeader = mvcResp.getHeader(HttpHeaders.CONTENT_TYPE);

                    final boolean hasBody   = raw != null && !raw.isBlank();
                    final boolean isJsonCT  = isJsonContentType(ctHeader);
                    final boolean looksJson = hasBody && looksLikeJson(raw);

                    JsonNode body = null;
                    if (hasBody && (isJsonCT || looksJson)) {
                        try {
                            body = jsonMapper.readTree(raw);
                        } catch (Exception parseEx) {
                            // Don’t bomb the test on non-JSON bodies; just log and proceed with raw text
                            log.debug("[FixtureEngine] Non-JSON body (ct='{}') could not be parsed as JSON: {}",
                                    ctHeader, rootMessage(parseEx));
                        }
                    } else if (hasBody && log.isTraceEnabled()) {
                        log.trace("[FixtureEngine] Skipping JSON parse for non-JSON response (ct='{}', sample='{}')",
                                ctHeader, Util.Logging.truncate(raw, 80));
                    }

                    Model.ResponseEnvelope env = new Model.ResponseEnvelope(mvcResp.getStatus(), copyHeaders(mvcResp), body, raw);

                    // 5.5) DEBUG response preview
                    if (log.isDebugEnabled()) {
                        log.debug("[FixtureEngine] response preview: status={} headers={} body={}",
                                env.status(),
                                Util.Logging.previewRespHeaders(env.headers()),
                                Util.Logging.previewNode(body));
                    }

                    // 6) Expected status (flexible & optional)
                    log.debug("FHI: examining Expected status ");
                    if (!statusMatcher.matches(spec.expectedStatus(), env.status())) 
                    {   log.debug("FHI Expected status is NOT as expected");
                        String bodyPreview = (raw == null || raw.isBlank()) ? "<empty>" : Util.Logging.truncate(raw, 500);
                        final String message = String.format(
                            Locale.ROOT,
                            "Unexpected HTTP %d for '%s' %s %s, expected=%s. Body=%s",
                            env.status(), name, method, uri, expectedStatusPreview(spec.expectedStatus()), bodyPreview
                        );
                        if (lax) {
                            log.info("[FixtureEngine] Unexpected return status, but authorized in lax mode, so OK! : {}", message);
                        } else {
                            log.warn("[FixtureEngine] {}", message);
                            throw new AssertionError(message);
                        }
                    }

                    // 7) Finish line + duration
                    final long tookMs = (System.nanoTime() - t0) / 1_000_000L;
                    log.info("[FixtureEngine] <<< Finished executing fixture: [{}, {}, {}] with status: [{}], in {} ms",
                            name, method, uri, env.status(), tookMs);


                    // === 8) Save variables only if JSON ===
                    if (spec.save() != null && !spec.save().isEmpty()) {
                        if (env.body() != null) {
                            DocumentContext ctx = JsonPath.parse(raw);
                            for (Map.Entry<String, String> e : spec.save().entrySet()) {
                                vars.put(e.getKey(), ctx.read(e.getValue()));
                            }
                            if (log.isDebugEnabled()) log.debug("[FixtureEngine]    saved vars from '{}': {}", name, spec.save().keySet());
                        } else if (log.isDebugEnabled()) {
                            log.debug("[FixtureEngine]    save skipped for '{}': response is not JSON", name);
                        }
                    }

                    if (log.isTraceEnabled()) log.trace("[FixtureEngine] Raw body (len={}): {}", raw == null ? 0 : raw.length(), Util.Logging.truncate(raw, 4000));
                    return env;

                } catch (RuntimeException re) {
                    log.warn("[FixtureEngine] ✖ [{}] {} failed: {}", method, name, rootMessage(re));
                    throw re;
                } catch (Exception e) {
                    log.warn("[FixtureEngine] ✖ [{}] {} errored: {}", method, name, rootMessage(e));
                    throw new RuntimeException("Error executing fixture '" + name + "'", e);
                }
            }

            /* === helpers =========================================================== */

            private static boolean isJsonContentType(String ctHeader) {
                if (ctHeader == null) return false;
                // Be tolerant of charset/parameters and vendor types
                String ct = ctHeader.toLowerCase(Locale.ROOT);
                return ct.contains("application/json")
                    || ct.contains("+json"); // e.g., application/problem+json
            }

            private static boolean looksLikeJson(String body) {
                if (body == null) return false;
                String s = body.stripLeading();
                return (s.startsWith("{") || s.startsWith("["));
            }

            private static boolean requiresCsrf(String method) {
                if (method == null) return false;
                String m = method.toUpperCase(Locale.ROOT);
                return m.equals("POST") || m.equals("PUT") || m.equals("PATCH") || m.equals("DELETE");
            }

            private static String safeName(org.springframework.security.core.Authentication a) {
                try { return a.getName(); } catch (Exception ignore) { return "(unknown)"; }
            }

            private static String rootMessage(Throwable t) {
                if (t == null) return "(no message)";
                String m = t.getMessage();
                if (m != null && !m.isBlank()) return m;
                Throwable c = t.getCause();
                if (c != null && c.getMessage() != null && !c.getMessage().isBlank()) return c.getMessage();
                return t.toString();
            }

            /* HTTP utils */

            private static MockHttpServletRequestBuilder toRequestBuilder(String method, URI uri) {
                String m = (method == null) ? "GET" : method.toUpperCase(Locale.ROOT);
                switch (m) {
                    case "GET":     return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(uri);
                    case "POST":    return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(uri);
                    case "PUT":     return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(uri);
                    case "PATCH":   return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(uri);
                    case "DELETE":  return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(uri);
                    case "HEAD":    return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head(uri);
                    case "OPTIONS": return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(uri);
                    default: throw new IllegalArgumentException("Unsupported HTTP method: " + method);
                }
            }

            private static String appendQuery(String endpoint, Map<String,String> query) {
                if (query == null || query.isEmpty()) return endpoint;
                StringBuilder sb = new StringBuilder(endpoint);
                sb.append(endpoint.contains("?") ? "&" : "?");
                boolean first = true;
                for (Map.Entry<String, String> e : query.entrySet()) {
                    if (!first) sb.append("&");
                    first = false;
                    sb.append(urlEncode(e.getKey())).append("=").append(urlEncode(e.getValue()));
                }
                return sb.toString();
            }

            private static String urlEncode(String s) {
                try { return URLEncoder.encode(s, StandardCharsets.UTF_8.toString()); }
                catch (Exception e) { throw new RuntimeException(e); }
            }

            private static Map<String, List<String>> copyHeaders(MockHttpServletResponse r) {
                Map<String, List<String>> h = new LinkedHashMap<>();
                for (String name : r.getHeaderNames()) h.put(name, new ArrayList<>(r.getHeaders(name)));
                return h;
            }

            private static String safeMethod(String method) { return method == null ? "GET" : method; }

            private static String expectedStatusPreview(JsonNode expected) {
                if (expected == null || expected.isNull()) return "(none)";
                return expected.toString();
            }
        }
    }

    /* =======================
       basedOn materialization (deep-merge payloads, same file only)
       ======================= */

    private static Model.FixtureSuite materializeBasedOn(ObjectMapper mapper, Model.FixtureSuite original) {
        if (original == null || original.fixtures() == null || original.fixtures().isEmpty()) return original;

        Map<String, Model.FixtureCall> byName = new LinkedHashMap<>();
        for (Model.FixtureCall f : original.fixtures()) {
            if (f.name() != null && !f.name().isBlank()) {
                if (byName.put(f.name(), f) != null) {
                    throw new IllegalStateException("Duplicate fixture name before basedOn processing: " + f.name());
                }
            }
        }

        List<Model.FixtureCall> out = new ArrayList<>(original.fixtures().size());
        for (Model.FixtureCall child : original.fixtures()) {
            String parentName = firstNonBlank(child.getBasedOn(), child.getBaseOn());
            if (parentName == null || parentName.isBlank()) {
                validateGroupVsHttp(child);
                out.add(child);
                continue;
            }

            Model.FixtureCall parent = byName.get(parentName);
            if (parent == null) {
                throw new IllegalArgumentException("basedOn refers to unknown fixture '" + parentName + "' (must be in same file)");
            }

            // Merge: scalars override, headers/query shallow-merge, save REPLACE, fixtures REPLACE, payload deep-merge
            Model.FixtureCall merged = new Model.FixtureCall();

            merged.setName(child.name()); // keep child's name
            // Scalars
            merged.setMethod(firstNonBlank(child.method(), parent.method()));
            merged.setEndpoint(firstNonBlank(child.endpoint(), parent.endpoint()));
            merged.setExpectedStatus((child.expectedStatus() != null) ? child.expectedStatus() : parent.expectedStatus());
            // Maps (headers/query): shallow-merge, child wins
            merged.setHeaders(mergeMap(parent.headers(), child.headers()));
            merged.setQuery(mergeMap(parent.query(), child.query()));

            // SAVE: REPLACE (no merge)
            merged.setSave(child.save() != null ? child.save() : parent.save());
            // FIXTURES (group list): REPLACE
            merged.setFixtures(child.fixtures() != null ? child.fixtures() : parent.fixtures());

            // Payload (deep-merge objects; arrays/scalars replace) with textual JSON coercion
            JsonNode resultPayload = deepMergePayload(mapper, parent.payload(), child.payload());
            merged.setPayload(resultPayload);

            validateGroupVsHttp(merged);
            out.add(merged);
        }

        Model.FixtureSuite suite = new Model.FixtureSuite();
        suite.setFixtures(out);
        return suite;
    }

    /** Ensure a fixture is either a group OR an HTTP call, not both. */
    private static void validateGroupVsHttp(Model.FixtureCall f) {
        boolean isGroup = f.fixtures() != null;
        boolean isHttp  =
            (f.endpoint() != null && !f.endpoint().isBlank()) ||
            (f.method()   != null && !f.method().isBlank())   ||
            f.payload() != null ||
            f.expectedStatus() != null ||
            (f.headers() != null && !f.headers().isEmpty()) ||
            (f.query()   != null && !f.query().isEmpty())   ||
            (f.save()    != null && !f.save().isEmpty());
        if (isGroup && isHttp) {
            throw new IllegalStateException("Fixture '" + f.name() + "' cannot define both 'fixtures' and HTTP fields");
        }
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
    private static Map<String,String> mergeMap(Map<String,String> parent, Map<String,String> child) {
        if ((parent == null || parent.isEmpty()) && (child == null || child.isEmpty())) return child;
        Map<String,String> out = new LinkedHashMap<>();
        if (parent != null) out.putAll(parent);
        if (child != null) out.putAll(child);
        return out;
    }

    /** If the node is textual and looks like JSON, parse it to a JSON tree; otherwise return as is. */
    private static JsonNode coerceJsonTextToNode(ObjectMapper mapper, JsonNode n) {
        if (n == null) return null;
        if (n.isTextual()) {
            String s = n.asText().trim();
            boolean looksJson = (s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]"));
            if (looksJson) {
                try { return mapper.readTree(s); } catch (Exception ignore) { /* keep textual */ }
            }
        }
        return n;
    }

    private static JsonNode deepMergePayload(ObjectMapper mapper, JsonNode parent, JsonNode child) {
        parent = coerceJsonTextToNode(mapper, parent);
        child  = coerceJsonTextToNode(mapper, child);

        if (child == null) return parent;
        if (parent == null) return child;

        // If child is array or scalar → replace
        if (child.isArray() || child.isValueNode()) return child;

        // Deep-merge objects
        if (child.isObject() && parent.isObject()) {
            ObjectNode merged = mapper.createObjectNode();
            // copy parent
            parent.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue().deepCopy()));
            // merge/override child
            child.fields().forEachRemaining(e -> {
                String k = e.getKey();
                JsonNode childVal = e.getValue();
                JsonNode parentVal = merged.get(k);
                if (childVal != null && childVal.isObject() && parentVal != null && parentVal.isObject()) {
                    merged.set(k, deepMergePayload(mapper, parentVal, childVal)); // recursive objects
                } else {
                    merged.set(k, childVal); // replace arrays/scalars or add new fields
                }
            });
            return merged;
        }

        // Types differ or parent not object → replace
        return child;
    }

    /* =======================
       Group helpers
       ======================= */

    private static boolean isGroupFixture(Model.FixtureCall f) {
        // A group is any fixture that *declares* a fixtures list (even empty → no-op group)
        return f != null && f.fixtures() != null;
    }


    private void runGroupFixture(String label, List<String> list, String baseDir, boolean lax) {
        if (list == null || list.isEmpty()) {
            log.info("[FixtureEngine] {} is empty; nothing to run.", label);
            return;
        }

        // Resolve in declared order
        List<Engine.PlanItem> plan = new ArrayList<>(list.size());
        for (String fname : list) {
            Model.FixtureCall local = byName.get(fname);
            if (local != null) {
                plan.add(new Engine.PlanItem(local, fixturesBaseDir));
                continue;
            }
            IO.ClasspathFixtureRepository.Resolved r = repo.findFirstByName(testClass, cfg, fname, yamlMapper);
            if (r == null) {
                String attempted = repo.candidateAncestorPaths(testClass, cfg).toString();
                if (lax) {
                    log.warn("[FixtureEngine] (lax) {} → unknown child fixture '{}'; skipping. Looked under: {}", label, fname, attempted);
                    continue; // best-effort: skip unknown child in lax mode
                }
                throw new IllegalArgumentException(
                    "Unknown fixture referenced in " + label + ": '" + fname + "'.\nSearched under: " + attempted
                );
            }
            plan.add(new Engine.PlanItem(r.call, r.baseDir));
        }

        // Execute each item in order; in lax mode, tolerate any error and continue
        for (Engine.PlanItem it : plan) {
            try {
                executor.execute(it.call, it.baseDir, vars, lax); // executor already tolerates status mismatches when lax=true
            } catch (Throwable t) {
                if (lax) {
                    log.warn("[FixtureEngine] (lax) {} → child '{}' failed — skipping. Cause: {}", label, it.name(), t.toString());
                    // continue with next child
                } else {
                    // strict: stop immediately
                    if (t instanceof RuntimeException) throw (RuntimeException) t;
                    throw new RuntimeException("Error executing fixture '" + it.name() + "' in " + label, t);
                }
            }
        }
    }



    /** Small utilities (logging helpers). */
    static final class Util {
        static final class Logging {
            static String truncate(String s, int max) {
                if (s == null) return null;
                if (s.length() <= max) return s;
                return s.substring(0, max) + " ...(" + (s.length() - max) + " more chars)";
            }

            static Map<String,Object> previewVars(Map<String,Object> vars) {
                Map<String,Object> out = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : vars.entrySet()) {
                    String k = e.getKey();
                    Object v = e.getValue();
                    out.put(k, k.toLowerCase(Locale.ROOT).contains("token") ? "***" : v);
                }
                return out;
            }

            static String previewHeaders(Map<String,String> headers) {
                if (headers == null || headers.isEmpty()) return "{}";
                Map<String,String> out = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    String k = e.getKey();
                    String v = e.getValue();
                    out.put(k, k.equalsIgnoreCase(HttpHeaders.AUTHORIZATION) ? "****" : v);
                }
                return out.toString();
            }

            /** Pretty-print response headers (Map<String, List<String>>), masking Authorization. */
            static String previewRespHeaders(Map<String, List<String>> headers) {
                if (headers == null || headers.isEmpty()) return "{}";
                Map<String,String> flat = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                    String k = e.getKey();
                    List<String> vals = e.getValue();
                    String joined = (vals == null || vals.isEmpty()) ? "" : String.join(",", vals);
                    flat.put(k, k.equalsIgnoreCase(HttpHeaders.AUTHORIZATION) ? "****" : joined);
                }
                return flat.toString();
            }

            static String previewNode(JsonNode n) {
                if (n == null) return "(none)";
                try { return n.toPrettyString(); } catch (Exception e) { return n.toString(); }
            }

            static String previewValue(Object v) {
                if (v == null) return "null";
                String s = String.valueOf(v);
                return s.length() > 200 ? s.substring(0, 200) + " …" : s;
            }
        }
    }

    /** POJOs for fixtures + response. */
    static final class Model {
        /** Root of the fixtures file (YAML). */
        static final class FixtureSuite {
            private List<FixtureCall> fixtures;

            public List<FixtureCall> fixtures() { return fixtures; }
            public void setFixtures(List<FixtureCall> fixtures) { this.fixtures = fixtures; }
        }

        /** One fixture row (HTTP fixture or group fixture when 'fixtures:' present). */
        static final class FixtureCall {
            private String name;
            private String method;
            private String endpoint;
            private Map<String,String> headers;
            private Map<String,String> query;
            private JsonNode payload;        // YAML object/array OR text ("classpath:..." or raw JSON string)
            private Map<String,String> save; // varName -> JSONPath
            private JsonNode expectedStatus; // int | "2xx"/"3xx"/"4xx"/"5xx" | "201" | [ ... any of those ... ]
            // basedOn within same file
            private String basedOn; // canonical
            private String baseOn;  // alias
            // group-as-fixture: if present, indicates this row is a group
            private List<String> fixtures;

            public String name() { return name; }
            public String method() { return method; }
            public String endpoint() { return endpoint; }
            public Map<String, String> headers() { return headers; }
            public Map<String, String> query() { return query; }
            public JsonNode payload() { return payload; }
            public Map<String, String> save() { return save; }
            public JsonNode expectedStatus() { return expectedStatus; }
            public String getBasedOn() { return basedOn; }
            public String getBaseOn() { return baseOn; }
            public List<String> fixtures() { return fixtures; }

            public void setName(String name) { this.name = name; }
            public void setMethod(String method) { this.method = method; }
            public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
            public void setHeaders(Map<String, String> headers) { this.headers = headers; }
            public void setQuery(Map<String, String> query) { this.query = query; }
            public void setPayload(JsonNode payload) { this.payload = payload; }
            public void setSave(Map<String, String> save) { this.save = save; }
            public void setExpectedStatus(JsonNode expectedStatus) { this.expectedStatus = expectedStatus; }
            public void setBasedOn(String basedOn) { this.basedOn = basedOn; }
            public void setBaseOn(String baseOn) { this.baseOn = baseOn; }
            public void setFixtures(List<String> fixtures) { this.fixtures = fixtures; }
        }

        /** Response wrapper. */
        static final class ResponseEnvelope {
            private final int status;
            private final Map<String, List<String>> headers;
            private final JsonNode body;
            private final String raw;
            public ResponseEnvelope(int status, Map<String, List<String>> headers, JsonNode body, String raw) {
                this.status = status; this.headers = headers; this.body = body; this.raw = raw;
            }
            public int status() { return status; }
            public Map<String, List<String>> headers() { return headers; }
            public JsonNode body() { return body; }
            public String raw() { return raw; }
        }
    }

    // Groups are represented as fixtures with a 'fixtures:' list.
}
