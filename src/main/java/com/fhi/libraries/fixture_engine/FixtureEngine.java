package com.fhi.libraries.fixture_engine;

import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;

import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;


import lombok.extern.slf4j.Slf4j;

/**
 * See readme.md file.
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

    // Standard Strict Config (throws exception on missing path)
    public static final Configuration JSONPATH_CONF_STRICT = Configuration.defaultConfiguration();

    // Lax (aka Lenient) Config (returns null on missing path)
    public static final Configuration JSONPATH_CONF_LAX = Configuration.defaultConfiguration()
                                                        .addOptions(Option.SUPPRESS_EXCEPTIONS);


    // JsonPath Configuration with safe defaults (Lenient)
    // This is the library that reads JSON paths in the "save" in the yaml fixtures.
    // With Option.SUPPRESS_EXCEPTIONS:
    //   If parent is null, asking for $.parent.child.value simply returns null.
    //   If you make a typo like $.parnet, it returns null (instead of crashing).
    private Configuration jsonPathConfig = Configuration.defaultConfiguration()
                                                        .addOptions(Option.SUPPRESS_EXCEPTIONS);

    public void setJsonPathConfig(Configuration jsonPathConfig) {
        this.jsonPathConfig = jsonPathConfig;
    }
    public Configuration getJsonPathConfig() {
        return this.jsonPathConfig;
    }


    // Flag to control overwrite behavior
    private boolean varsOverwriteStrict = false;

    /**
     * Enable or disable strict mode for variable handling.
     *
     * <p>When enabled, any attempt to overwrite an existing variable
     * will throw an exception instead of logging a warning.
     */
    public void setVarsStrict(boolean strict) {
        this.varsOverwriteStrict = strict;
    }

    // =====================================================================
    // Internal
    // =====================================================================

    /**
     * Used for debugging vars in varsDump()
     */
    private static final ObjectMapper VARS_DUMP_MAPPER = new ObjectMapper()
                                                              .enable(SerializationFeature.INDENT_OUTPUT);

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
    {   return callFixture(name, false, false);
    }

    /**
     * Conveniance for a lax call.
     */
    public Model.ResponseEnvelope callFixtureLax(String name)
    {   return callFixture(name, true, false);
    }

    /**
     * Execute a fixture or a group by name.
     *
     * @param name the fixture (or group) name
     * @param lax  if true, expected-status mismatches are logged (warning) and won't fail the test.
     *             For group fixtures, each child is executed in lax mode.
     * @param jsonPathLax if true, the library that reads JSON paths in the "save" in the yaml fixtures
     *             will have a lax (aka leniant) configuration.
     *             Meaning: If parent is null, asking for $.parent.child.value simply returns null.
     *             If you make a typo like $.parnet, it returns null (instead of crashing).
     * @return {@code null} for group fixtures; for single fixtures, the response envelope.
     */
    public Model.ResponseEnvelope callFixture(String name, boolean lax, boolean jsonPathLax)
    {
        Objects.requireNonNull(name, "name");
        Resolved r = resolveByName(name);

        if (isGroupFixture(r.call)) {
            validateGroupVsHttp(r.call);
            final String label = "group '" + name + "'" + (lax ? " (lax)" : "");
            runGroupFixture(label, r.call.getFixtures(), r.baseDir, lax, jsonPathLax);
            return null;
        }

        // Single fixture
        validateGroupVsHttp(r.call);
        try {
            return executor.execute(r.call, r.baseDir, vars, lax, jsonPathLax);
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
     * Alias
     */
    public Model.ResponseEnvelope run(String name)
    {   return callFixture(name);
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
     * Side effects: none (pure function).
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

        Model.ResponseEnvelope env = executor.execute(r.call, r.baseDir, vars, false, false);
        String raw = env.raw();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Fixture '" + callName + "' returned an empty body; cannot read: " + jsonPath);
        }

        /* Do NOT store return: */
        Object value = JsonPath.parse(raw).read(jsonPath);
        if (log.isDebugEnabled()) {
             log.debug("Extracted {} from '{}': {}", jsonPath, callName, Util.Logging.previewValue(value));
        }
        // No variable stashing here (pure function). Callers can stash explicitly if desired:
        // fx.varsPut("_last", value); fx.varsPut(callName + "." + inferredKey, value); etc.
        return value;
    }


    /**
     * Execute a fixture (or group) with a set of per-call variable overrides.
     *
     * <p>The callScoped map is applied only for the duration of this call:
     * <ul>
     *   <li><b>Reads:</b> when templating, header/query resolution, or payload
     *       substitution looks up a variable, the engine will check the call-scoped
     *       overrides first. If the key is not present there, it falls back to the
     *       engine’s global variables.</li>
     *   <li><b>Writes:</b> when a fixture specifies a "save:" block, the
     *       extracted values are always written into the engine’s global variables,
     *       never into the call-scoped overrides. This ensures saved IDs or tokens
     *       are available to subsequent fixtures and test code.</li>
     * </ul>
     *
     * <p>The overrides are ephemeral: once the call returns, they are discarded.
     * Global variables are never modified unless the fixture itself performs a save
     * operation or the test code calls {@link #varsPut(String, Object)} directly.
     *
     * <p>Examples:
     * <pre>{@code
     * // Global default
     * fx.varsPut("buyer", "Alice");
     *
     * // Call with temporary override (buyer = Bob)
     * Map<String,Object> scoped = Map.of("buyer", "Bob", "region", 3);
     * fx.callFixture("create_order", scoped);
     *
     * // After the call:
     * //   - "buyer" in global vars is still "Alice"
     * //   - "region" is not present in global vars
     * //   - any saved vars (e.g., "orderId") are in global vars
     * }</pre>
     *
     * @param name       the fixture (or group) name
     * @param callScoped per-call variable overrides (not mutated; keys shadow globals)
     * @return {@code null} for group fixtures; for single fixtures, the response envelope
     * @throws IllegalArgumentException if {@code name} is not found
     * @throws RuntimeException for execution failures in strict mode
     */
    public Model.ResponseEnvelope callFixture(String name, Map<String,Object> callScoped) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(callScoped, "callScoped");

        // Layered view for this call (and group children, if any)
        Map<String,Object> mergedVars = new CallScopedVars(callScoped, this.vars, this::varsPut);

        Resolved r = resolveByName(name);

        if (isGroupFixture(r.call)) {
            validateGroupVsHttp(r.call);
            final String label = "group '" + name + "'";
            runGroupFixture(label, r.call.getFixtures(), r.baseDir, /*lax*/ false, false, mergedVars);
            return null;
        }

        validateGroupVsHttp(r.call);
        return executor.execute(r.call, r.baseDir, mergedVars, /*lax*/ false, false);
    }

    /**
     * Clears (empties) the Fixture Engine context variables map.
     */
    public void varsClear() { vars.clear(); }

    /**
     * Put a variable in the Fixture Engine context variables map.
     *
     * - Strict mode: throws if the key already exists.
     * - Non-strict: overwrites and logs a WARN if there was a previous value.
     *
     * @return the previous value associated with key, or {@code null} if there was none
     * @throws IllegalStateException if strict mode is enabled and the key already exists
     */
    public Object varsPut(String key, Object value)
    {
        varsRequireValidKey(key);

        if (varsOverwriteStrict && vars.containsKey(key)) {
            throw new IllegalStateException("Var '" + key + "' already exists and strict mode is enabled");
        }

        Object prev = vars.put(key, value);

        if (!varsOverwriteStrict && prev != null) {
            log.warn("Overwriting var '{}' (old={}, new={}). This is permitted because varsOverwriteStrict is set to false.",
                    key, prev, value);
        }
        return prev;
    }

    /**
     * Put a variable in the Fixture Engine context variables map, only if absent (never overwrites).
     *
     * @return true if the value was set; false if a value was already present
     */
    public boolean varsPutIfAbsent(String key, Object value) {
        varsRequireValidKey(key);
        return !vars.containsKey(key) && vars.put(key, value) == null;
    }

    /**
     * Get a variable from the Fixture Engine context variables map.
     */
    public Object varsGet(String key) {
        if (!vars.containsKey(key)) {
            throw new IllegalStateException("Var '" + key + "' does not exist");
        }
        return vars.get(key);
    }

    /**
     * Get a variable from the Fixture Engine context variables map.
     */
    public <T> T varsGet(String key, Class<T> type) {
        if (!vars.containsKey(key)) {
            throw new IllegalStateException("Var '" + key + "' does not exist");
        }
        Object val = vars.get(key);
        try {
            return type.cast(val);
        } catch (ClassCastException e) {
            throw new IllegalStateException(
                "Var '" + key + "' is not of type " + type.getSimpleName() +
                " (actual: " + (val == null ? "null" : val.getClass().getSimpleName()) + ")",
                e
            );
        }
    }

    /**
     * Convenience: Get a variable from the Fixture Engine context variables map, as a String.
     * Delegates to the generic varsGet() for type safety and error handling.
     */
    public String varsGetString(String key) {
        return varsGet(key, String.class);
    }

    /**
     * Convenience: Get a variable from the Fixture Engine context variables map, as a Long, handling Integer/Long/String 
     * conversions automatically.
     * 
     * @param key The name variable
     */
    public Long varsGetLong(String key) {
        if (!vars.containsKey(key)) {
            throw new IllegalStateException("Var '" + key + "' does not exist");
        }
        
        Object val = vars.get(key);
        
        // Handle Integer, Long, Double, etc.
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        
        // Handle "123" strings
        if (val instanceof String) {
            try {
                return Long.parseLong((String) val);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Var '" + key + "' is a String but cannot be parsed as Long: " + val, e);
            }
        }
        
        throw new IllegalStateException(
            "Var '" + key + "' is not a number (actual type: " + 
            (val == null ? "null" : val.getClass().getSimpleName()) + ")"
        );
    }

    /**
     * Retrieves a variable from the Fixture Engine context variables map, and attempts to parse it into an {@link Instant}.
     * 
     * This method is designed to be "painless" by automatically trying several 
     * common ISO-8601 formats (Instant, ZonedDateTime, and OffsetDateTime).
     * 
     * @param key The name of the captured variable
     * @return The parsed {@link Instant}, or {@code null} if the variable doesn't exist
     * @throws DateTimeParseException if the string cannot be parsed by standard ISO formats
     * @throws ClassCastException if the variable is not a String
     */
    public Instant varsGetInstant(String key) {
        String value = varsGetString(key);
        if (value == null) {
            return null;
        }

        try {
            // Priority 1: Direct Instant parsing (e.g., 2026-02-27T18:00:00Z)
            return Instant.parse(value);
        } catch (DateTimeParseException e1) {
            try {
                // Priority 2: Full ZonedDateTime (e.g., 2026-02-27T18:00:00+01:00[Europe/Paris])
                return ZonedDateTime.parse(value).toInstant();
            } catch (DateTimeParseException e2) {
                // Priority 3: OffsetDateTime (e.g., 2026-02-27T18:00:00+01:00)
                return OffsetDateTime.parse(value).toInstant();
            }
        }
    }

    /**
     * Retrieves a variable and parses it into an {@link Instant} using a specific formatter.
     * 
     * Use this version for non-standard API date formats (e.g., "dd/MM/yyyy HH:mm").
     * Note: If the pattern does not contain timezone information, you may need to 
     * use {@link LocalDateTime} and provide a {@link ZoneId}.
     * 
     * @param key The name of the captured variable
     * @param formatter The custom {@link DateTimeFormatter} to use
     * @return The parsed {@link Instant}, or {@code null} if the variable doesn't exist
     */
    public Instant varsGetInstant(String key, DateTimeFormatter formatter) {
        String value = varsGetString(key);
        if (value == null) {
            return null;
        }
        
        // We parse as a temporal accessor and convert to instant
        return formatter.parse(value, Instant::from);
    }


    /**
     * Convenience: Get a variable as a List of a specific type.
     * <p>
     * Handles safe conversion for numeric types (e.g., if the underlying JSON parser 
     * stored integers but Longs got requested).
     */
    public <T> List<T> varsGetList(String key, Class<T> elementType) {
        if (!vars.containsKey(key)) {
            throw new IllegalStateException("Var '" + key + "' does not exist");
        }
        Object val = vars.get(key);
        
        if (val == null) return null;
        
        if (!(val instanceof List)) {
             throw new IllegalStateException(
                "Var '" + key + "' is not a List. Actual type: " + val.getClass().getName()
            );
        }

        List<?> rawList = (List<?>) val;
        List<T> result = new ArrayList<>(rawList.size());

        for (int i = 0; i < rawList.size(); i++) {
            Object item = rawList.get(i);
            
            if (item == null) {
                result.add(null);
                continue;
            }

            // Numeric conversion magic (Integer -> Long, etc.)
            if (Number.class.isAssignableFrom(elementType) && item instanceof Number) {
                Number num = (Number) item;
                if (elementType == Long.class) {
                    result.add(elementType.cast(num.longValue()));
                } else if (elementType == Integer.class) {
                    result.add(elementType.cast(num.intValue()));
                } else if (elementType == Double.class) {
                    result.add(elementType.cast(num.doubleValue()));
                } else {
                    // Fallback for other number types
                    result.add(elementType.cast(item)); 
                }
            } else {
                // Standard cast
                try {
                    result.add(elementType.cast(item));
                } catch (ClassCastException e) {
                    throw new IllegalStateException(String.format(
                        "Element at index %d in var '%s' is not %s (actual: %s)",
                        i, key, elementType.getSimpleName(), item.getClass().getSimpleName()), e);
                }
            }
        }
        
        return result;
    }

    /**
     * Checks if a variable is present in the Fixture Engine context variables map.
     */
    public boolean varsHas(String key) {
        return vars.containsKey(key);
    }

    /**
     * Returns a live, unmodifiable view of the current Fixture Engine context variables map.
     * <p>
     * Any future changes to the underlying vars will be reflected,
     * but callers cannot mutate the map directly.
     */
    public Map<String, Object> varsView() {
        return Collections.unmodifiableMap(vars);
    }

    /**
     * Returns the current variables map as a pretty-printed JSON string.
     *
     * <p>Intended for debugging and test logging only. Does not expose the
     * underlying mutable map.
     */
    public String varsDump() {
        try
        {   return VARS_DUMP_MAPPER.writeValueAsString(vars);
        }
        catch (JsonProcessingException e)
        {
            log.error("Failed to dump vars", e);
            return vars.toString();
        }
    }

    private static void varsRequireValidKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Key is null/blank");
        }
    }

    // =====================================================================
    //   Builder / construction
    // =====================================================================

    private final Class<?> testClass;
    private final Model.FixtureSuite suite;
    private final Map<String, Model.FixtureCall> byName;

    // Concurrent allows tests to be run in parallel
    //#private final ConcurrentMap<String, Object> vars = new ConcurrentHashMap<>();
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

    // ===== Unlimited-depth basedOn materialization cache =====

    /** Key for memoizing materialized fixtures by scope (baseDir) and name. */
    private static final class MatKey {
        final String baseDir; // classpath dir where the fixture is defined
        final String name;
        MatKey(String baseDir, String name) { this.baseDir = baseDir; this.name = name; }
        public boolean equals(Object o){ if(this==o)return true; if(!(o instanceof MatKey))return false;
            MatKey k=(MatKey)o; return Objects.equals(baseDir,k.baseDir)&&Objects.equals(name,k.name); }
        public int hashCode(){ return Objects.hash(baseDir,name); }
        public String toString(){ return baseDir+":"+name; }
    }

    /** Unlimited-depth, hierarchy-aware cache. */
    private final Map<MatKey, Model.FixtureCall> materializedCache = new LinkedHashMap<>();

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

        // IMPORTANT: keep RAW suite (no pre-materialization at load time)
        this.suite = loaded.suite;
        this.fixturesBaseDir = loaded.baseDir;
        this.repo = repo;

        // === NEW: Load hierarchical vars =======================================
        // Lower (closer) level completely overrides higher-level vars.
        Map<String,Object> initialVars = loadHierarchicalVars(testClass, cfg, yamlMapper);
        if (!initialVars.isEmpty()) {
            // Seed the engine vars without logging/strictness noise (initial boot).
            for (Map.Entry<String,Object> e : initialVars.entrySet()) {
                String k = e.getKey();
                if (k != null && !k.isBlank()) {
                    vars.put(k, e.getValue());
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("[FixtureEngine] initial vars loaded: {}", Util.Logging.previewVars(vars));
            }
        }
        // === END NEW ============================================================

        // Index fixtures by name (fail fast on duplicates) and validate structure
        Map<String, Model.FixtureCall> index = new LinkedHashMap<>();
        List<Model.FixtureCall> calls = (suite.fixtures() == null) ? Collections.emptyList() : suite.fixtures();
        for (Model.FixtureCall f : calls) {
            if (f.getName() == null || f.getName().isBlank()) {
                throw new IllegalStateException("Fixture with missing/blank 'name' in " + fixturesBaseDir + "/" + DEFAULT_FIXTURES_BASENAME);
            }
            validateGroupVsHttp(f);
            if (index.put(f.getName(), f) != null) {
                // Fail on name collision
                throw new IllegalStateException("Duplicate fixture name: " + f.getName());
            }
        }
        this.byName = Collections.unmodifiableMap(index);

        // Runtime helpers & wiring
        Runtime.StatusMatcher matcher = new Runtime.StatusMatcher();
        Runtime.Templating templating = new Runtime.SimpleTemplating();
        Runtime.PayloadResolver payloadResolver = new Runtime.PayloadResolver(yamlMapper);

        // Use JSON mapper for request/response bodies
        this.executor = new Runtime.HttpExecutor(mockMvc, jsonMapper, templating, payloadResolver, matcher, builderAuthSupplier);
        this.groups = new Engine.GroupRunner(item -> executor.execute(item.call, item.baseDir, vars, false, false));
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

    /** Resolve a name to a concrete, fully materialized fixture (closest → parents), with its baseDir. */
    private Resolved resolveByName(String name)
    {
        // 1) Try closest file first
        Model.FixtureCall local = byName.get(name);
        if (local != null) {
            // Materialize deeply (same-file and cross-file, unlimited depth) from the local file's baseDir
            Model.FixtureCall mat = materializeDeep(local, fixturesBaseDir, new ArrayDeque<>(), new HashSet<>());
            return new Resolved(mat, fixturesBaseDir);
        }

        // 2) Hierarchical lookup upwards (closest → ... → root) starting from the test class location
        IO.ClasspathFixtureRepository.Resolved found = repo.findFirstByName(testClass, cfg, name, yamlMapper);
        if (found == null) {
            List<String> attempted = repo.candidateAncestorPaths(testClass, cfg);
            throw new IllegalArgumentException("Fixture/Group not found by name: " + name
                    + ". Looked under: " + attempted);
        }

        // Ensure deep materialization from the found scope
        Model.FixtureCall mat = materializeDeep(found.call, found.baseDir, new ArrayDeque<>(), new HashSet<>());
        return new Resolved(mat, found.baseDir);
    }

    private static final class Resolved {
        final Model.FixtureCall call;
        final String baseDir;
        Resolved(Model.FixtureCall call, String baseDir) { this.call = call; this.baseDir = baseDir; }
    }

    /**
     * Fully materialize a fixture: follow {@code basedOn} across files to any depth.
     * Merges at each step with child precedence, using existing merge rules:
     *  - Scalars: child overrides
     *  - headers/query (maps): shallow merge, child wins
     *  - save / fixtures (lists-of-names): REPLACE
     *  - payload: deep-merge objects; arrays/scalars replace
     *
     * Cycle-safe with a visiting set + stack (human-friendly chain on error).
     */
    private Model.FixtureCall materializeDeep(Model.FixtureCall node,
                                              String nodeBaseDir,
                                              Deque<MatKey> stack,
                                              Set<MatKey> visiting)
    {
        if (node == null) return null;

        final String parentName = firstNonBlank(node.getBasedOn(), node.getBaseOn());
        final String nodeName = (node.getName() == null || node.getName().isBlank()) ? "<unnamed>" : node.getName();
        final MatKey key = new MatKey(nodeBaseDir, nodeName);

        // Cache
        Model.FixtureCall cached = materializedCache.get(key);
        if (cached != null) return cached;

        // No inheritance → normalize + cache
        if (parentName == null || parentName.isBlank()) {
            Model.FixtureCall normalized = cloneWithoutBasedOn(node);
            materializedCache.put(key, normalized);
            return normalized;
        }

        // Cycle guard
        if (!visiting.add(key)) {
            StringBuilder sb = new StringBuilder("Cycle in basedOn: ");
            for (MatKey k : stack) sb.append(k).append(" -> ");
            sb.append(key);
            throw new IllegalStateException(sb.toString());
        }
        stack.addLast(key);

        // Resolve parent by searching from THIS node’s file directory upwards (closest → parents → root)
        IO.ClasspathFixtureRepository.Resolved parentResolved =
                repo.findFirstByNameFromBaseDir(testClass, cfg, nodeBaseDir, parentName, yamlMapper);

        if (parentResolved == null) {
            String attempted = repo.candidateAncestorPathsFromBaseDir(nodeBaseDir, cfg).toString();
            throw new IllegalArgumentException(
                "basedOn refers to unknown fixture '" + parentName + "'. Looked under (from " + nodeBaseDir + "): " + attempted
            );
        }

        // Recurse
        Model.FixtureCall materializedParent =
                materializeDeep(parentResolved.call, parentResolved.baseDir, stack, visiting);

        // Merge parent → child (child overrides)
        Model.FixtureCall merged = new Model.FixtureCall();
        merged.setName(node.getName());
        merged.setMethod(firstNonBlank(node.getMethod(), materializedParent.getMethod()));
        merged.setEndpoint(firstNonBlank(node.getEndpoint(), materializedParent.getEndpoint()));
        merged.setExpectedStatus(node.getExpectedStatus() != null ? node.getExpectedStatus() : materializedParent.getExpectedStatus());
        merged.setHeaders(mergeMap(materializedParent.getHeaders(), node.getHeaders()));
        merged.setQuery(mergeMap(materializedParent.getQuery(), node.getQuery()));
        // For "save": replace instead of merge (no inheritance unless explicitly set on the child)
        merged.setSave(node.getSave() != null ? node.getSave() : materializedParent.getSave());
        // For "fixtures" (group list): replace instead of merge
        merged.setFixtures(node.getFixtures() != null ? node.getFixtures() : materializedParent.getFixtures());
        merged.setMultipart(node.getMultipart() != null ? node.getMultipart() : materializedParent.getMultipart());
        JsonNode payload = deepMergePayload(yamlMapper, materializedParent.getPayload(), node.getPayload());
        merged.setPayload(payload);


        // Clear inheritance markers on the final node
        merged.setBasedOn(null);
        merged.setBaseOn(null);

        validateGroupVsHttp(merged);

        materializedCache.put(key, merged);
        stack.removeLast();
        visiting.remove(key);
        return merged;
    }

    /** Shallow clone of a call, with basedOn/baseOn cleared. */
    private static Model.FixtureCall cloneWithoutBasedOn(Model.FixtureCall src) {
        Model.FixtureCall c = new Model.FixtureCall();
        c.setName(src.getName());
        c.setMethod(src.getMethod());
        c.setEndpoint(src.getEndpoint());
        c.setHeaders(src.getHeaders()==null?null:new LinkedHashMap<>(src.getHeaders()));
        c.setQuery(src.getQuery()==null?null:new LinkedHashMap<>(src.getQuery()));
        c.setPayload(src.getPayload()); // JSON nodes are fine to share for our usage
        c.setSave(src.getSave()==null?null:new LinkedHashMap<>(src.getSave()));
        c.setExpectedStatus(src.getExpectedStatus());
        c.setFixtures(src.getFixtures()==null?null:new ArrayList<>(src.getFixtures()));
        c.setMultipart(src.getMultipart() == null ? null : new ArrayList<>(src.getMultipart()));
        c.setBasedOn(null);
        c.setBaseOn(null);

        return c;
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
            String name() { return call.getName(); }
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

            /** Finds the first (closest) occurrence of a fixture by name across ancestor files (starting from test package). */
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

                        // NOTE: do NOT materialize here; scan raw and return the raw hit.
                        for (Model.FixtureCall f : raw.fixtures()) {
                            if (name.equals(f.getName())) {
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

            /** Returns candidate ancestor classpaths starting at an arbitrary baseDir (closest → root). */
            List<String> candidateAncestorPathsFromBaseDir(String baseDir, Engine.FixtureConfig cfg) {
                List<String> out = new ArrayList<>();
                String cur = (baseDir == null) ? "" : baseDir;
                while (cur.endsWith("/")) cur = cur.substring(0, cur.length() - 1);

                if (!cur.isEmpty()) out.add(cur + "/" + cfg.fileName);

                // Walk up until we reach cfg.rootPath
                while (!cur.equals(cfg.rootPath)) {
                    int i = cur.lastIndexOf('/');
                    if (i <= 0) break;
                    cur = cur.substring(0, i);
                    out.add(cur + "/" + cfg.fileName);
                }

                String root = cfg.rootPath + "/" + cfg.fileName;
                if (!out.contains(root)) out.add(root);
                return out;
            }

            /** Finds the first (closest) occurrence of a fixture by name, starting from an arbitrary baseDir. */
            Resolved findFirstByNameFromBaseDir(Class<?> testClass, Engine.FixtureConfig cfg,
                                                String startBaseDir, String name, ObjectMapper yamlMapper) {
                List<String> candidates = candidateAncestorPathsFromBaseDir(startBaseDir, cfg);
                ClassLoader tccl = Thread.currentThread().getContextClassLoader();
                ClassLoader fallback = FixtureEngine.class.getClassLoader();
                for (String cp : candidates) {
                    URL url = firstNonNullUrl(tccl, testClass.getClassLoader(), fallback, cp);
                    if (url == null) continue;
                    try (InputStream in = url.openStream()) {
                        Model.FixtureSuite raw = yamlMapper.readValue(in, Model.FixtureSuite.class);
                        if (raw.fixtures() == null || raw.fixtures().isEmpty()) continue;

                        for (Model.FixtureCall f : raw.fixtures()) {
                            if (name.equals(f.getName())) {
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

        static final class PayloadResolver 
        {
            private final ObjectMapper mapper;
            PayloadResolver(ObjectMapper mapper) { this.mapper = mapper; }

            JsonNode resolve(JsonNode payload, String baseDir, Map<String, Object> vars, Templating tpl) throws IOException {
                if (payload == null) return null;

                // CASE 1: Block Scalar (String) - e.g. payload: >
                if (payload.isTextual()) {
                    String txt = payload.asText().trim();
                    
                    // 1. Interpolate variables FIRST
                    // We use our helper because it safely handles unquoted integers ({{id}} -> 3)
                    // If your 'tpl.apply' does this correctly, you can use that. 
                    // But the helper is safer for JSON generation.
                    txt = replaceVariablesInString(txt, vars); 
                    
                    // 2. Also run the original Templating engine (optional, if you have other macros)
                    // txt = tpl.apply(txt, vars); 

                    // 3. Handle 'classpath:' prefix
                    String lower = txt.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("classpath:")) {
                        String rawPath = txt.substring(txt.indexOf(':') + 1).trim();
                        // Recursive call to load and resolve the file content
                        JsonNode fileContent = loadClasspathPayload(baseDir, rawPath);
                        return resolve(fileContent, baseDir, vars, tpl);
                    }

                    // 4. Raw JSON string? -> Parse it!
                    // Now that {{id}} is replaced with 3, readTree will succeed.
                    if (looksLikeJson(txt)) {
                        try {
                            return mapper.readTree(txt);
                        } catch (JsonProcessingException e) {
                            // Fallback: If parsing fails, return as simple text
                            // This helps debug if interpolation missed something
                            return mapper.getNodeFactory().textNode(txt);
                        }
                    }

                    // 5. Plain string -> keep as text
                    return mapper.getNodeFactory().textNode(txt);
                }

                // CASE 2: Structural YAML/JSON (Map/Array) - e.g. payload: { key: val }
                // Recursively walk the tree and template string values
                return templateNodeStrings(payload, vars, tpl);
            }

            private String replaceVariablesInStringOLD(String template, Map<String, Object> variables) {
                if (template == null || !template.contains("{{")) {
                    return template;
                }

                String result = template;
                for (Map.Entry<String, Object> entry : variables.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();
                    String placeholder = "{{" + key + "}}";
                    
                    if (result.contains(placeholder)) {
                        // Primitives (Int/Bool): Insert as-is (e.g. 3 or true)
                        // Objects/Strings: toString() (e.g. "someValue")
                        String replacement = (value == null) ? "null" : String.valueOf(value);
                        result = result.replace(placeholder, replacement);
                    }
                }
                return result;
            }

            private String replaceVariablesInString(String template, Map<String, Object> variables) {
                if (template == null || !template.contains("{{")) {
                    return template;
                }

                // Regex to find {{ key }}
                // Matches {{ followed by any character except } repeatedly, ending with }}
                Pattern pattern = Pattern.compile("\\{\\{([^}]+)\\}\\}");
                Matcher matcher = pattern.matcher(template);
                StringBuffer sb = new StringBuffer();

                while (matcher.find()) {
                    // group(1) is the inner text (e.g. "param.link")
                    String key = matcher.group(1).trim(); 

                    if (variables.containsKey(key)) {
                        Object value = variables.get(key);
                        String replacement = (value == null) ? "null" : String.valueOf(value);
                        
                        // Quote the replacement to ensure $ or \ in the value don't break the matcher
                        matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                    } else {
                        // Warning for missing variable
                        log.warn("[FixtureEngine] Interpolation warning: Variable '{{}}' found in payload " +
                                 "but missing from vars! (Leaving as-is). This will probably cause the " +
                                 "fixture cal to fail.", key);

                        // Keep the original {{key}} in the text so the error is visible in the output/logs
                        matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
                    }
                }
                matcher.appendTail(sb);
                return sb.toString();
            }


            byte[] readResourceBytes(String baseDir, String rawPath) 
            {
                // Resolve path (relative or absolute)
                String path = rawPath.startsWith("/") ? rawPath.substring(1) : (baseDir + "/" + rawPath);

                URL url = null;
                ClassLoader tccl = Thread.currentThread().getContextClassLoader();
                if (tccl != null) url = tccl.getResource(path);
                if (url == null) {
                    ClassLoader fallback = FixtureEngine.class.getClassLoader();
                    if (fallback != null) url = fallback.getResource(path);
                }

                if (url == null) throw new IllegalArgumentException("Resource not found: " + rawPath);

                try (InputStream in = url.openStream()) {
                    return in.readAllBytes();
                } catch (IOException e) {
                    throw new RuntimeException("Failed reading bytes from " + path, e);
                }
            }

            JsonNode resolveOLD(JsonNode payload, String baseDir, Map<String,Object> vars, Templating tpl) throws IOException {
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
                return execute(spec, baseDir, vars, false, false);
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
             * @param jsonPathLax see callFixture(...)
             */
            Model.ResponseEnvelope execute(Model.FixtureCall spec, String baseDir, 
                                           Map<String,Object> vars, boolean lax,
                                           boolean jsonPathLax 
                                        )
            {
                log.debug("Lax mode: lax = {}", lax);
                final long t0 = System.nanoTime();
                final String name   = (spec.getName() == null || spec.getName().isBlank()) ? "<unnamed>" : spec.getName();
                final String method = safeMethod(spec.getMethod());

                Configuration jsonPathConfig = jsonPathLax 
                                                ? JSONPATH_CONF_LAX 
                                                : JSONPATH_CONF_STRICT;
                try {
                    // Endpoint must be present after materialization
                    if (spec.getEndpoint() == null || spec.getEndpoint().isBlank()) {
                        throw new IllegalArgumentException("Fixture '" + name + "' has no 'endpoint' after basedOn resolution. " +
                                "Check that its base defines 'endpoint' (or legacy 'url').");
                    }

                    // 1) Resolve endpoint, headers, query with templating (values only for maps)
                    final String             endpoint = tpl.apply(spec.getEndpoint(), vars);
                    final Map<String,String> headers0 = tpl.applyMapValuesOnly(spec.getHeaders(), vars);
                    final Map<String,String> query    = tpl.applyMapValuesOnly(spec.getQuery(), vars);
                    final URI                uri      = URI.create(appendQuery(endpoint, query));

                    // 2) Resolve payload (YAML/JSON node or text with classpath: include)
                    final JsonNode payloadNode = payloads.resolve(spec.getPayload(), baseDir, vars, tpl);

                    // 3) Human-friendly start + DEBUG details
                    log.info("[FixtureEngine] >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
                    log.info("[FixtureEngine] >>> Executing fixture:  [{}, {}, {}]", name, method, uri);
                    if (log.isDebugEnabled()) {
                        log.debug("[FixtureEngine] more info: expected={} headers={} query={} vars={} payload={}",
                                expectedStatusPreview(spec.getExpectedStatus()),
                                Util.Logging.previewHeaders(headers0),
                                (query == null || query.isEmpty() ? "{}" : query.toString()),
                                Util.Logging.previewVars(vars),
                                Util.Logging.previewNode(payloadNode));

                        //#log.debug("[FixtureEngine] spec=\n{}", jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec));
                    }

                    // 4) Build request
                    MockHttpServletRequestBuilder req;
                    Map<String,String> headers = (headers0 == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(headers0);
                    
                    // --- IF MULTIPART ---
                    if (spec.getMultipart() != null && !spec.getMultipart().isEmpty()) 
                    {   log.debug("[FixtureEngine] Multipart detected");
                        // Remove explicit Content-Type header from the fixture definition.
                        // We MUST let MockMvc generate "multipart/form-data; boundary=..." 
                        // automatically. If we send "application/json" here, the backend
                        // will not parse the parts, causing "Required part 'cmd' is not present".
                        headers.remove(HttpHeaders.CONTENT_TYPE);
                        headers.remove("Content-Type");

                        // 1. Create Multipart Builder
                        MockMultipartHttpServletRequestBuilder multiReq = MockMvcRequestBuilders.multipart(uri);

                        // 2. Fix Method (multipart defaults to POST, but we might need PUT)
                        String effectiveMethod = (method == null) ? "POST" : method.toUpperCase(Locale.ROOT);
                        multiReq.with(r -> { r.setMethod(effectiveMethod); return r; });

                        // 3. Add Parts
                        for (Model.MultipartDef part : spec.getMultipart()) {
                            String pName = tpl.apply(part.name, vars);
                            String pType = (part.type != null) ? part.type.toLowerCase() : "json";
                            String pFilename = tpl.apply(part.filename, vars); // optional

                            byte[] contentBytes;
                            String contentType;

                            if ("file".equals(pType)) {
                                // For files, the 'body' is the path string (e.g. "classpath:files/doc.pdf")
                                String path = part.body.asText();
                                if (path.toLowerCase().startsWith("classpath:")) {
                                    path = path.substring(10).trim();
                                }
                                // Use the new helper to read raw bytes
                                contentBytes = payloads.readResourceBytes(baseDir, tpl.apply(path, vars));
                                // Auto-detect or default content type
                                contentType = (pFilename != null && pFilename.endsWith(".pdf")) ? "application/pdf"
                                            : (pFilename != null && pFilename.endsWith(".png")) ? "image/png"
                                            : "application/octet-stream";
                            } else {
                                // For JSON/Text, resolve the body (handles templating {{id}})
                                JsonNode resolvedPartBody = payloads.resolve(part.body, baseDir, vars, tpl);
                                if ("json".equals(pType) || (resolvedPartBody.isContainerNode())) {
                                    contentBytes = jsonMapper.writeValueAsBytes(resolvedPartBody);
                                    contentType = "application/json";
                                    if (pFilename == null) pFilename = ""; // JSON parts usually have empty filename
                                } else {
                                    // Plain text
                                    contentBytes = resolvedPartBody.asText().getBytes(StandardCharsets.UTF_8);
                                    contentType = "text/plain";
                                }
                            }

                            multiReq.file(new org.springframework.mock.web.MockMultipartFile(
                                    pName, pFilename, contentType, contentBytes
                            ));
                        }
                        req = multiReq;
                    } 

                    // --- IF STANDARD, NON MULTIPART ---
                    else 
                    {   log.debug("[FixtureEngine] Standard, NOT Multipart");
                        req = toRequestBuilder(method, uri);
                        if (payloadNode != null) {
                            req.content(jsonMapper.writeValueAsBytes(payloadNode));
                            if (headers == null || !headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
                                req.contentType(MediaType.APPLICATION_JSON);
                            }
                        }
                    }



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
                        log.debug("[FixtureEngine] using Authentication principal={}", safeName(auth));
                    }
                    if (requiresCsrf(method)) {
                        req.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf());
                        log.debug("[FixtureEngine] CSRF token added for {}", method);
                    }

                    if (!headers.isEmpty()) for (Map.Entry<String,String> e : headers.entrySet()) req.header(e.getKey(), e.getValue());
                    if (payloadNode != null) {
                        req.content(jsonMapper.writeValueAsBytes(payloadNode));
                        if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
                            req.contentType(MediaType.APPLICATION_JSON);
                        }
                    }

                    // === 5) Execute + tolerant parsing (non-JSON safe) ===
                    MockHttpServletResponse mvcResp = mockMvc.perform(req).andDo(print()).andReturn().getResponse();

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
                            // Tolerate non-JSON or malformed JSON — keep raw text and continue
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
                    if (!statusMatcher.matches(spec.getExpectedStatus(), env.status()))
                    {   log.debug("FHI Expected status is NOT as expected");
                        String bodyPreview = (raw == null || raw.isBlank()) ? "<empty>" : Util.Logging.truncate(raw, 500);
                        final String message = String.format(
                            Locale.ROOT,
                            "Unexpected HTTP %d for '%s' %s %s, expected=%s. Body=%s",
                            env.status(), name, method, uri, expectedStatusPreview(spec.getExpectedStatus()), bodyPreview
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
                    log.info("[FixtureEngine] <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<");

                    // === 8) Save variables only if JSON ===
                    if (spec.getSave() != null && !spec.getSave().isEmpty()) {
                        if (env.body() != null) {
                            DocumentContext ctx = JsonPath.using(jsonPathConfig).parse(raw);
                            for (Map.Entry<String, String> e : spec.getSave().entrySet()) {
                                // NOTE: initial vars are loaded at construction; runtime 'save' still writes directly.
                                // If you later want to enforce strictness here, route via varsPut / varsPutIfAbsent.
                                vars.put(e.getKey(), ctx.read(e.getValue()));
                            }
                            log.debug("[FixtureEngine]    saved vars from '{}': {}", name, spec.getSave().keySet());
                        } else if (log.isDebugEnabled()) {
                            log.debug("[FixtureEngine]    save skipped for '{}': response is not JSON", name);
                        }
                    }

                    if (log.isTraceEnabled()) log.trace("[FixtureEngine] Raw body (len={}): {}", raw == null ? 0 : raw.length(), Util.Logging.truncate(raw, 4000));
                    return env;

                } catch (RuntimeException re) {
                    log.warn("[FixtureEngine] X [{}] {} failed: {}", method, name, rootMessage(re));
                    throw re;
                } catch (Exception e) {
                    // NOTE: Non-JSON bodies no longer cause JsonParseException to escape here.
                    log.warn("[FixtureEngine] X [{}] {} errored: {}", method, name, rootMessage(e));
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
       Merge helpers
       ======================= */

    /** Ensure a fixture is either a group OR an HTTP call, not both. */
    private static void validateGroupVsHttp(Model.FixtureCall f) {
        boolean isGroup = f.getFixtures() != null;
        boolean isHttp  =
            (f.getEndpoint() != null && !f.getEndpoint().isBlank()) ||
            (f.getMethod()   != null && !f.getMethod().isBlank())   ||
            f.getPayload() != null ||
            f.getExpectedStatus() != null ||
            (f.getHeaders() != null && !f.getHeaders().isEmpty()) ||
            (f.getQuery()   != null && !f.getQuery().isEmpty())   ||
            (f.getSave()    != null && !f.getSave().isEmpty());
        if (isGroup && isHttp) {
            throw new IllegalStateException("Fixture '" + f.getName() + "' cannot define both 'fixtures' and HTTP fields");
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
        return f != null && f.getFixtures() != null;
    }



    private void runGroupFixture(String label, List<String> list, String baseDir, boolean lax, boolean jsonPathLax) {
        runGroupFixture(label, list, baseDir, lax, jsonPathLax, this.vars);
    }

    /**
     * Run a group fixture using the provided vars map (e.g., a call-scoped overlay).
     */
    private void runGroupFixture(String label, List<String> list, String baseDir, boolean lax, boolean jsonPathLax, Map<String,Object> varsForCall) {
        if (list == null || list.isEmpty()) {
            log.info("[FixtureEngine] {} is empty; nothing to run.", label);
            return;
        }

        // Resolve in declared order (using resolveByName to ensure deep materialization)
        List<Engine.PlanItem> plan = new ArrayList<>(list.size());
        for (String fname : list) {
            try {
                Resolved r = resolveByName(fname);
                plan.add(new Engine.PlanItem(r.call, r.baseDir));
            } catch (Throwable t) {
                if (lax) {
                    String attempted = repo.candidateAncestorPaths(testClass, cfg).toString();
                    log.warn("[FixtureEngine] (lax) {} → unknown or failed child fixture '{}'; skipping. Searched under: {}. Cause: {}",
                            label, fname, attempted, t.toString());
                    continue;
                }
                throw t instanceof RuntimeException ? (RuntimeException) t
                        : new RuntimeException("Error resolving fixture '" + fname + "' in " + label, t);
            }
        }

        // Execute each item in order with the provided vars map
        for (Engine.PlanItem it : plan) {
            try {
                executor.execute(it.call, it.baseDir, varsForCall, lax, jsonPathLax);
            } catch (Throwable t) {
                if (lax) {
                    log.warn("[FixtureEngine] (lax) {} → child '{}' failed — skipping. Cause: {}", label, it.name(), t.toString());
                    // continue with next child
                } else {
                    if (t instanceof RuntimeException) throw (RuntimeException) t;
                    throw new RuntimeException("Error executing fixture '" + it.name() + "' in " + label, t);
                }
            }
        }
    }


    /* =======================
       Initial vars loader
       ======================= */

    /**
     * Load a top-level {@code vars:} map from the hierarchical fixture files:
     * root → package ancestors → (optional) per-test-class directory.
     * <p>
     * The closest file to the test class completely overrides any higher-level vars
     * (no merging). If no file defines {@code vars}, returns an empty map.
     */
    @SuppressWarnings("unchecked")
    private Map<String,Object> loadHierarchicalVars(Class<?> testClass, Engine.FixtureConfig cfg, ObjectMapper yamlMapper) {
        List<String> candidates = repo.candidateAncestorPaths(testClass, cfg);
        Map<String,Object> last = null;

        ClassLoader tccl     = Thread.currentThread().getContextClassLoader();
        ClassLoader testCl   = testClass.getClassLoader();
        ClassLoader fallback = FixtureEngine.class.getClassLoader();

        for (String cp : candidates) {
            URL url = (tccl != null ? tccl.getResource(cp) : null);
            if (url == null && testCl != null) url = testCl.getResource(cp);
            if (url == null && fallback != null) url = fallback.getResource(cp);
            if (url == null) continue;

            try (InputStream in = url.openStream()) {
                Model.FixtureSuite suite = yamlMapper.readValue(in, Model.FixtureSuite.class);
                Map<String,Object> varsFromFile = suite.vars();
                if (varsFromFile != null && !varsFromFile.isEmpty()) {
                    // Completely replace (closest wins)
                    last = new LinkedHashMap<>(varsFromFile);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed reading vars from " + cp, e);
            }
        }
        return (last == null) ? Collections.emptyMap() : last;
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
            /** Optional top-level variables loaded at engine construction (closest file wins). */
            private Map<String,Object> vars;

            public List<FixtureCall> fixtures() { return fixtures; }
            public void setFixtures(List<FixtureCall> fixtures) { this.fixtures = fixtures; }

            public Map<String,Object> vars() { return vars; }
            public void setVars(Map<String,Object> vars) { this.vars = vars; }
        }

        /** Definition of a single part in a multipart request. */
        static final class MultipartDef {
            public String name;
            public String type;      // "json", "file", "text" (default: json if body is object, text otherwise)
            public String filename;  // filename to report (required for files)
            public JsonNode body;    // The content (JSON object, or "classpath:..." string)

            // getters/setters needed for Jackson
            public void setName(String name) { this.name = name; }
            public void setType(String type) { this.type = type; }
            public void setFilename(String filename) { this.filename = filename; }
            public void setBody(JsonNode body) { this.body = body; }
        }


        /** One fixture row (HTTP fixture or group fixture when 'fixtures:' present). */
        static final class FixtureCall {
            private String name;
            private String method;
            private String endpoint;
            private Map<String,String> headers;
            private Map<String,String> query;
            private JsonNode payload;        // YAML object/array OR text ("classpath:..." or raw JSON string)
            private JsonNode body;           // Alias for payload
            private Map<String,String> save; // varName -> JSONPath
            private JsonNode expectedStatus; // int | "2xx"/"3xx"/"4xx"/"5xx" | "201" | [ ... any of those ... ]
            // basedOn (now unlimited depth; resolved on demand)
            private String basedOn; // canonical
            private String baseOn;  // alias of basedOn
            // group-as-fixture: if present, indicates this row is a group
            private List<String> fixtures;
            private List<MultipartDef> multipart;
            // jsonPath mode: "lax" ("lenient") or "strict" ()
            // Lenient returns null for missing paths; Strict throws PathNotFoundException.
            public String jsonPathMode;

            // === legacy bridge: accept "url" in YAML and map it into "endpoint" ===
            private String url; // not used directly; setter maps to endpoint if endpoint is missing

            public String getName() { return name; }
            public String getMethod() { return method; }
            public String getEndpoint() { return endpoint; }
            public Map<String, String> getHeaders() { return headers; }
            public Map<String, String> getQuery() { return query; }
            public JsonNode getPayload() { return payload; }
            public JsonNode getBody() { return body; }
            public Map<String, String> getSave() { return save; }
            public JsonNode getExpectedStatus() { return expectedStatus; }
            public String getBasedOn() { return basedOn; }
            public String getBaseOn() { return baseOn; }
            public List<String> getFixtures() { return fixtures; }
            public List<MultipartDef> getMultipart() { return multipart; }

            public void setName(String name) { this.name = name; }
            public void setMethod(String method) { this.method = method; }
            public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
            public void setHeaders(Map<String, String> headers) { this.headers = headers; }
            public void setQuery(Map<String, String> query) { this.query = query; }
            public void setPayload(JsonNode payload) { this.payload = payload; }
            public void setBody(JsonNode body) { this.payload = body; }
            public void setSave(Map<String, String> save) { this.save = save; }
            public void setExpectedStatus(JsonNode expectedStatus) { this.expectedStatus = expectedStatus; }
            public void setBasedOn(String basedOn) { this.basedOn = basedOn; }
            public void setBaseOn(String baseOn) { this.baseOn = baseOn; }
            public void setFixtures(List<String> fixtures) { this.fixtures = fixtures; }
            public void setMultipart(List<MultipartDef> multipart) { this.multipart = multipart; }

            /** Legacy: when YAML provides 'url', treat it as 'endpoint' if endpoint is unset. */
            public void setUrl(String url) {
                this.url = url;
                if ((this.endpoint == null || this.endpoint.isBlank()) && url != null && !url.isBlank()) {
                    this.endpoint = url;
                }
            }
            public String getUrl() { return url; }
        }

        /** Response wrapper. */
        public static final class ResponseEnvelope {
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




    /**
     * Map wrapper that represents the combination of:
     * <ul>
     *   <li><b>Per-call scoped overrides</b> (provided by the caller)</li>
     *   <li><b>Global engine variables</b> (owned by the FixtureEngine)</li>
     * </ul>
     *
     * <p>Semantics:
     * <ul>
     *   <li><b>Reads (get/containsKey):</b> first consult the scoped overrides;
     *       if the key is not present there, fall back to the global vars.</li>
     *   <li><b>Iteration (entrySet/size):</b> presents a merged snapshot view,
     *       with scoped overrides taking precedence on key collisions.</li>
     *   <li><b>Writes (put):</b> always delegated to the engine’s global
     *       {@link FixtureEngine#varsPut(String, Object)} (via the provided writer).
     *       This preserves strict mode and overwrite-warning semantics, and ensures
     *       that any values saved during fixture execution become visible globally.</li>
     *   <li><b>Overrides are immutable:</b> the scoped map is copied on construction
     *       and never mutated by this wrapper.</li>
     * </ul>
     *
     * <p>This class is used internally by
     * {@link FixtureEngine#callFixture(String, Map)} to implement call-scoped
     * variable overrides. Test code does not normally need to use it directly.
     */
    private static final class CallScopedVars extends AbstractMap<String,Object> {
        private final Map<String,Object> scoped;    // per-call overrides (immutable snapshot is fine)
        private final Map<String,Object> globals;   // underlying engine vars (for reads)
        private final BiFunction<String,Object,Object> writer; // e.g., FixtureEngine::varsPut

        CallScopedVars(Map<String,Object> scoped,
                    Map<String,Object> globals,
                    BiFunction<String,Object,Object> writer) {
            this.scoped  = (scoped == null || scoped.isEmpty()) ? Collections.emptyMap() : new LinkedHashMap<>(scoped);
            this.globals = Objects.requireNonNull(globals, "globals");
            this.writer  = Objects.requireNonNull(writer, "writer");
        }

        @Override public Object get(Object key) {
            if (!(key instanceof String)) return null;
            String k = (String) key;
            return scoped.containsKey(k) ? scoped.get(k) : globals.get(k);
        }

        @Override public boolean containsKey(Object key) {
            if (!(key instanceof String)) return false;
            String k = (String) key;
            return scoped.containsKey(k) || globals.containsKey(k);
        }

        @Override public Object put(String key, Object value) {
            // Route writes through writer (e.g., varsPut) to honor strict mode and WARN logs
            return writer.apply(key, value);
        }

        @Override public Set<Entry<String,Object>> entrySet() {
            LinkedHashMap<String,Object> merged = new LinkedHashMap<>(globals);
            merged.putAll(scoped); // scoped wins on key collisions
            return Collections.unmodifiableSet(merged.entrySet());
        }

        @Override public int size() {
            HashSet<String> keys = new HashSet<>(globals.keySet());
            keys.addAll(scoped.keySet());
            return keys.size();
        }
    }


}
