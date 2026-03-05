package com.fhi.libraries.moxter;

import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;

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
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.assertj.core.api.Assertions;
import org.assertj.core.api.ObjectAssert;
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

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * See readme.md file.
 */
@Slf4j
public final class Moxter
{
    // =====================================================================
    // Top-level config (easy to tweak)
    // =====================================================================

    /** The current version of Moxter. */
    public static final String VERSION = "1.0.0-SNAPSHOT";

    // Classpath root under which moxtures live (no leading/trailing slash).
    private static final String DEFAULT_MOXTURES_ROOT_PATH = "moxtures";

    // If true, look in a subfolder named after the test class simple name.
    private static final boolean DEFAULT_USE_PER_TESTCLASS_DIRECTORY = true;

    // Single accepted file name (with extension).
    private static final String DEFAULT_MOXTURES_BASENAME = "moxtures.yaml";

    // Standard Strict Config (throws exception on missing path)
    private static final Configuration JSONPATH_CONF_STRICT = Configuration.defaultConfiguration();

    // Lax (aka Lenient) Config (returns null on missing path)
    private static final Configuration JSONPATH_CONF_LAX = Configuration.defaultConfiguration()
                                                        .addOptions(Option.SUPPRESS_EXCEPTIONS);

    // JsonPath Configuration with safe defaults (Lenient)
    // This is the library that reads JSON paths in the "save" in the yaml moxtures.
    // With Option.SUPPRESS_EXCEPTIONS:
    //   - If parent is null, asking for $.parent.child.value simply returns null.
    //   - If you make a typo like $.parnet, it returns null (instead of crashing).
    private Configuration jsonPathConfig = Configuration.defaultConfiguration()
                                                        .addOptions(Option.SUPPRESS_EXCEPTIONS);

    // Overwrite behavior for variables.
    private boolean varsOverwriteStrict = false;

    /**
     * Sets the config for the library that reads JSON paths in the "save" in the yaml moxtures.
     * @param jsonPathConfig
     */
    public void setJsonPathConfig(Configuration jsonPathConfig) {
        this.jsonPathConfig = jsonPathConfig;
    }

    public Configuration getJsonPathConfig() {
        return this.jsonPathConfig;
    }

    /**
     * Enable or disable strict mode for variable handling.
     *
     * <p>When adding a variable to Moxter's var context map, and if the var already exists:
     * <p>- Strict mode: throws exception.
     * <p>- Non-strict: overwrites var and logs a WARN.
     */
    public void setVarsStrict(boolean strict) {
        this.varsOverwriteStrict = strict;
    }

    // =====================================================================
    // Internal
    // =====================================================================


    private final Class<?> testClass;
    private final Model.MoxtureFile suite;
    private final Map<String, Model.Moxture> byName;

    // Concurrent allows tests to be run in parallel
    //#private final ConcurrentMap<String, Object> vars = new ConcurrentHashMap<>();
    private final Map<String,Object> vars = new LinkedHashMap<>();

    private final Runtime.HttpExecutor executor;
    private final String moxturesBaseDir;

    // For hierarchical lookup
    private final IO.ClasspathMoxtureRepository repo;
    private final IO.MoxtureConfig cfg;
    private final ObjectMapper yamlMapper;
    // Keep JSON mapper for HTTP
    private final ObjectMapper jsonMapper;


    // Auth supplier (fixed or lazy) from builder
    private final java.util.function.Supplier<org.springframework.security.core.Authentication> builderAuthSupplier;

    // ===== Unlimited-depth basedOn materialization cache =====

    /** Unlimited-depth, hierarchy-aware cache. */
    private final Map<MaterializedKey, Model.Moxture> materializedCache = new LinkedHashMap<>();


    /**
     * Used for debugging vars in varsDump()
     */
    private static final ObjectMapper VARS_DUMP_MAPPER = new ObjectMapper()
                                                              .enable(SerializationFeature.INDENT_OUTPUT);

    // Cached global variables accessor
    private final MoxterVars globalScopeVars = new MoxterVars(this, null);




    // =====================================================================
    // Public API: moxture calls
    // =====================================================================

    /**
     * Creates a fluent {@link Builder} to configure and instantiate the Moxter engine.
     * 
     * <p>The provided test class acts as the anchor point for discovering YAML moxture 
     * definition files. Moxter uses the class's package structure and name to perform a 
     * hierarchical lookup for the {@code moxtures.yaml} file (e.g., starting from 
     * {@code moxtures/com/yourcompany/MyTest/moxtures.yaml} and walking up the directory tree).
     * 
     * <p><b>Example Usage:</b>
     * <pre>{@code
     * Moxter fx = Moxter.forTestClass(MyControllerTest.class)
     *                   .mockMvc(this.mockMvc)
     *                   .build();
     * }</pre>
     * 
     * If setting up Moxter in a parent class shared by multiple test classes, use {@code getClass()}:
     * <pre>{@code
     * Moxter fx = Moxter.forTestClass(getClass())  // dynamically resolves the child class name
     *                   .mockMvc(this.mockMvc)
     *                   .build();
     * }</pre>

     * @param testClass The test class that will execute the moxtures. Used strictly for 
     *                  classpath resource resolution and variable inheritance.
     * @return A new {@link Builder} to finish configuring the engine before calling {@code .build()}.
     */
    public static Builder forTestClass(Class<?> testClass)
    { return new Builder(testClass);
    }

    /** 
     * The factory method to spawn a transient caller
     */
    public MoxterCaller caller() {
        return new MoxterCaller(this);
    }


    // =====================================================================
    // Public API: variables
    // =====================================================================

    /**
     * Access Moxter's global-scope variables map.
     * 
     * <p>Use this API to interact with the global variables saved during your test execution.
     * This acts as the centralized "memory" for your API scenario, allowing you to pass 
     * IDs, tokens, or other state between moxture calls.
     * 
     * <p><b>Example Usage:</b>
     * <pre>{@code
     *    // Retrieve a saved variable with automatic type conversion:
     *    String name = mx.vars().read("name").asString();
     * 
     *    // If so sometimes no type conversion is explicitly needed:
     *    // Here assertion engines are usually clever, they figure out the typing themselves
     *    assertEquals("Snowy", mx.vars().get("petName")); // returns the raw variable as Object
     * 
     *    // Manually inject a variable in the global context for future moxtures to use (e.g., as {{status}})
     *    mx.vars().put("status", "AVAILABLE");
     * 
     *    // Clear the context completely
     *    mx.vars().clear();
     * }</pre>
     * 
     * @return The {@link MoxterVars} facade containing variable management methods.
     * @see MoxterVars
     */
    public MoxterVars vars() {
        return globalScopeVars;
    }

    /**
     * Access a moxture's locally-scope variables map.
     * 
     * <p>Works the same was as vars(), see that function.
     */
    public MoxterVars vars(String moxtureName) {
        return new MoxterVars(this, moxtureName);
    }

    /**
     * Get a variable defined within the moxture.
     *
     * @deprecated Use the 'Fluent API' instead i.e. {@link MoxterVars} which provides 
     *             a cleaner way to interact with variables.
     */
    @Deprecated(since = "1.0.0")    @SuppressWarnings("java:S1133")
    public Object moxVar(String moxtureName, String varName) 
    {   MaterializedMoxture r = resolveByName(moxtureName);
        return (r.moxt.getVars() != null) ? r.moxt.getVars().get(varName) : null;
    }

    /**
     * Clears (empties) Moxter's global-scope variables map.
     * 
     * @deprecated Use the 'Fluent API' instead i.e. {@link MoxterVars} which provides 
     *             a cleaner way to interact with variables.
     */
    @Deprecated(since = "1.0.0")    @SuppressWarnings("java:S1133")
    public void varsClear() { vars.clear(); }



    /**
     * Put a variable in Moxter's global-scope variables map, only if absent (never overwrites).
     *
     * @return true if the value was set; false if a value was already present
     * 
     * @deprecated Use the 'Fluent API' instead i.e. {@link MoxterVars} which provides 
     *             a cleaner way to interact with variables.
     */
    @Deprecated(since = "1.0.0")    @SuppressWarnings("java:S1133")
    public boolean varsPutIfAbsent(String key, Object value) {
        varsRequireValidKey(key);
        return !vars.containsKey(key) && vars.put(key, value) == null;
    }

    /**
     * Get a variable from Moxter's global-scope variables map.
     * 
     * @deprecated Use the 'Fluent API' instead i.e. {@link MoxterVars} which provides 
     *             a cleaner way to interact with variables.
     */
    @Deprecated(since = "1.0.0")    @SuppressWarnings("java:S1133")
    public Object varsGet(String key) {
        if (!vars.containsKey(key)) {
            throw new IllegalStateException("Var '" + key + "' does not exist");
        }
        return vars.get(key);
    }

    /**
     * Get a variable from Moxter's global-scope variables map.
     * 
     * @deprecated Use the 'Fluent API' instead i.e. {@link MoxterVars} which provides 
     *             a cleaner way to interact with variables.
     */
    @Deprecated(since = "1.0.0")    @SuppressWarnings("java:S1133")
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
     * Convenience: Get a variable from Moxter's global-scope variables map, as a String.
     * 
     * <p>Delegates to the generic varsGet() for type safety and error handling.
     * 
     * @deprecated Use the 'Fluent API' instead i.e. {@link MoxterVars} which provides 
     *             a cleaner way to interact with variables.
     */
    @Deprecated(since = "1.0.0")    @SuppressWarnings("java:S1133")
    public String varsGetString(String key) {
        return varsGet(key, String.class);
    }

    /**
     * Convenience: Get a variable from Moxter's global-scope variables map, as a Long, handling Integer/Long/String 
     * conversions automatically.
     * 
     * @param key The name variable
     * @deprecated Use the 'Fluent API' instead i.e. {@link MoxterVars} which provides 
     *             a cleaner way to interact with variables.
     */
    @Deprecated(since = "1.0.0")    @SuppressWarnings("java:S1133")
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
     * Retrieves a variable from Moxter's global-scope variables map, and attempts to parse it into an {@link Instant}.
     * 
     * This method is designed to be "painless" by automatically trying several 
     * common ISO-8601 formats (Instant, ZonedDateTime, and OffsetDateTime).
     * 
     * @param key The name of the captured variable
     * @return The parsed {@link Instant}, or {@code null} if the variable doesn't exist
     * @throws DateTimeParseException if the string cannot be parsed by standard ISO formats
     * @throws ClassCastException if the variable is not a String
     * @deprecated Use the 'Fluent API' instead i.e. {@link MoxterVars} which provides 
     *             a cleaner way to interact with variables.
     */
    @Deprecated(since = "1.0.0")    @SuppressWarnings("java:S1133")
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
     * @deprecated Use the 'Fluent API' instead i.e. {@link MoxterVars} which provides 
     *             a cleaner way to interact with variables.
     */
    @Deprecated(since = "1.0.0")    @SuppressWarnings("java:S1133")
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
     * 
     * @deprecated Use the 'Fluent API' instead i.e. {@link MoxterVars} which provides 
     *             a cleaner way to interact with variables.
     */
    @Deprecated(since = "1.0.0")    @SuppressWarnings("java:S1133")
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
     * Checks if a variable is present in Moxter's global-scope variables map.
     * 
     * @deprecated Use the 'Fluent API' instead i.e. {@link MoxterVars} which provides 
     *             a cleaner way to interact with variables.
     */
    @Deprecated(since = "1.0.0")    @SuppressWarnings("java:S1133")
    public boolean varsHas(String key) {
        return vars.containsKey(key);
    }


    /**
     * Returns a live, unmodifiable view of the current Moxture Engine context variables map.
     * 
     * <p>Any future changes to the underlying vars will be reflected,
     * but callers cannot mutate the map directly.
     * 
     * @deprecated Use the 'Fluent API' instead i.e. {@link MoxterVars} which provides 
     *             a cleaner way to interact with variables.
     */
    @Deprecated(since = "1.0.0")    @SuppressWarnings("java:S1133")
    public Map<String, Object> varsView() {
        return Collections.unmodifiableMap(vars);
    }

    /**
     * Returns the current variables map as a pretty-printed JSON string.
     *
     * <p>Intended for debugging and test logging only. Does not expose the
     * underlying mutable map.
     * 
     * @deprecated Use the 'Fluent API' instead i.e. {@link MoxterVars} which provides 
     *             a cleaner way to interact with variables.
     */
    @Deprecated(since = "1.0.0")    @SuppressWarnings("java:S1133")
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

    /**
     * @deprecated Used in the deprectaed vars* functions
     */
    @Deprecated(since = "1.0.0")    @SuppressWarnings("java:S1133")
    private static void varsRequireValidKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Key is null/blank");
        }
    }




    // =====================================================================
    //   Builder / construction
    // =====================================================================

    private Moxter(Class<?> testClass,
                          MockMvc mockMvc,
                          java.util.function.Supplier<org.springframework.security.core.Authentication> authSupplier)
    {
        this.testClass = Objects.requireNonNull(testClass, "testClass");
        Objects.requireNonNull(mockMvc, "mockMvc");
        this.builderAuthSupplier = authSupplier;

        // Build minimal config internally
        IO.MoxtureConfig cfg = new IO.MoxtureConfig(
                DEFAULT_MOXTURES_ROOT_PATH,
                DEFAULT_USE_PER_TESTCLASS_DIRECTORY,
                DEFAULT_MOXTURES_BASENAME
        );
        this.cfg = cfg;

        // YAML for reading moxtures/includes; JSON for HTTP I/O
        ObjectMapper yamlMapper = defaultYamlMapper();
        ObjectMapper jsonMapper = defaultJsonMapper();
        this.yamlMapper = yamlMapper;
        this.jsonMapper = jsonMapper;

        // Load closest moxtures file (read with YAML)
        IO.ClasspathMoxtureRepository repo = new IO.ClasspathMoxtureRepository(yamlMapper);
        IO.MoxtureRepository.LoadedSuite loaded = repo.loadFor(testClass, cfg);

        // IMPORTANT: keep RAW suite (no pre-materialization at load time)
        this.suite = loaded.suite;
        this.moxturesBaseDir = loaded.baseDir;
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
                log.debug("[Moxter] initial vars loaded: {}", Utils.Logging.previewVars(vars));
            }
        }
        // === END NEW ============================================================

        // Index moxtures by name (fail fast on duplicates) and validate structure
        Map<String, Model.Moxture> index = new LinkedHashMap<>();
        List<Model.Moxture> calls = (suite.moxtures() == null) ? Collections.emptyList() : suite.moxtures();
        for (Model.Moxture f : calls) {
            if (f.getName() == null || f.getName().isBlank()) {
                throw new IllegalStateException("Moxture with missing/blank 'name' in " + moxturesBaseDir + "/" + DEFAULT_MOXTURES_BASENAME);
            }
            validateMoxture(f);
            if (index.put(f.getName(), f) != null) {
                // Fail on name collision
                throw new IllegalStateException("Duplicate moxture name: " + f.getName());
            }
        }
        this.byName = Collections.unmodifiableMap(index);

        // Runtime helpers & wiring
        Runtime.StatusMatcher matcher = new Runtime.StatusMatcher();
        Runtime.Templating templating = new Runtime.SimpleTemplating();
        Runtime.PayloadResolver payloadResolver = new Runtime.PayloadResolver(yamlMapper);

        // Use JSON mapper for request/response bodies
        this.executor = new Runtime.HttpExecutor(mockMvc, jsonMapper, templating, payloadResolver, matcher, builderAuthSupplier);
    }




    // =====================================================================
    //   Private helpers
    // =====================================================================

    /** Resolve a name to a concrete, fully materialized moxture (closest → parents), with its baseDir. */
    private MaterializedMoxture resolveByName(String name)
    {
        // 1) Try closest file first
        Model.Moxture local = byName.get(name);
        if (local != null) {
            // Materialize deeply (same-file and cross-file, unlimited depth) from the local file's baseDir
            Model.Moxture mat = materializeDeep(local, moxturesBaseDir, new ArrayDeque<>(), new HashSet<>());
            return new MaterializedMoxture(mat, moxturesBaseDir);
        }

        // 2) Hierarchical lookup upwards (closest → ... → root) starting from the test class location
        IO.ClasspathMoxtureRepository.Resolved found = repo.findFirstByName(testClass, cfg, name, yamlMapper);
        if (found == null) {
            List<String> attempted = repo.candidateAncestorPaths(testClass, cfg);
            throw new IllegalArgumentException("Moxture/Group not found by name: " + name
                    + ". Looked under: " + attempted);
        }

        // Ensure deep materialization from the found scope
        Model.Moxture mat = materializeDeep(found.call, found.baseDir, new ArrayDeque<>(), new HashSet<>());
        return new MaterializedMoxture(mat, found.baseDir);
    }


    /**
     * Fully materialize a moxture: follow {@code basedOn} across files to any depth.
     * Merges at each step with child precedence, using existing merge rules:
     *  - Scalars: child overrides
     *  - headers/query (maps): shallow merge, child wins
     *  - save / moxtures (lists-of-names): REPLACE
     *  - payload: deep-merge objects; arrays/scalars replace
     *
     * Cycle-safe with a visiting set + stack (human-friendly chain on error).
     */
    private Model.Moxture materializeDeep(Model.Moxture node,
                                              String nodeBaseDir,
                                              Deque<MaterializedKey> stack,
                                              Set<MaterializedKey> visiting)
    {
        if (node == null) return null;

        final String parentName = firstNonBlank(node.getBasedOn(), node.getBaseOn());
        final String nodeName = (node.getName() == null || node.getName().isBlank()) ? "<unnamed>" : node.getName();
        final MaterializedKey key = new MaterializedKey(nodeBaseDir, nodeName);

        // Cache
        Model.Moxture cached = materializedCache.get(key);
        if (cached != null) return cached;

        // No inheritance → normalize + cache
        if (parentName == null || parentName.isBlank()) {
            Model.Moxture normalized = cloneWithoutBasedOn(node);
            materializedCache.put(key, normalized);
            return normalized;
        }

        // Cycle guard
        if (!visiting.add(key)) {
            StringBuilder sb = new StringBuilder("Cycle in basedOn: ");
            for (MaterializedKey k : stack) sb.append(k).append(" -> ");
            sb.append(key);
            throw new IllegalStateException(sb.toString());
        }
        stack.addLast(key);

        // Resolve parent by searching from THIS node’s file directory upwards (closest → parents → root)
        IO.ClasspathMoxtureRepository.Resolved parentResolved =
                repo.findFirstByNameFromBaseDir(testClass, cfg, nodeBaseDir, parentName, yamlMapper);

        if (parentResolved == null) {
            String attempted = repo.candidateAncestorPathsFromBaseDir(nodeBaseDir, cfg).toString();
            throw new IllegalArgumentException(
                "basedOn refers to unknown moxture '" + parentName + "'. Looked under (from " + nodeBaseDir + "): " + attempted
            );
        }

        // Recurse
        Model.Moxture materializedParent =
                materializeDeep(parentResolved.call, parentResolved.baseDir, stack, visiting);

        // Merge parent → child (child overrides)
        Model.Moxture merged = new Model.Moxture();
        merged.setName(node.getName());
        merged.setMethod(firstNonBlank(node.getMethod(), materializedParent.getMethod()));
        merged.setEndpoint(firstNonBlank(node.getEndpoint(), materializedParent.getEndpoint()));
        merged.setExpectedStatus(node.getExpectedStatus() != null ? node.getExpectedStatus() : materializedParent.getExpectedStatus());
        merged.setHeaders(mergeMap(materializedParent.getHeaders(), node.getHeaders()));
        merged.setVars(mergeMap(materializedParent.getVars(), node.getVars()));
        merged.setQuery(mergeMap(materializedParent.getQuery(), node.getQuery()));
        // For "save": replace instead of merge (no inheritance unless explicitly set on the child)
        merged.setSave(node.getSave() != null ? node.getSave() : materializedParent.getSave());
        // For "moxtures" (group list): replace instead of merge
        merged.setMoxtures(node.getMoxtures() != null ? node.getMoxtures() : materializedParent.getMoxtures());
        merged.setMultipart(node.getMultipart() != null ? node.getMultipart() : materializedParent.getMultipart());
        JsonNode payload = deepMergePayload(yamlMapper, materializedParent.getPayload(), node.getPayload());
        merged.setPayload(payload);



        // Clear inheritance markers on the final node
        merged.setBasedOn(null);
        merged.setBaseOn(null);

        validateMoxture(merged);

        materializedCache.put(key, merged);
        stack.removeLast();
        visiting.remove(key);
        return merged;
    }

    /** 
     * Shallow clone of a call, with basedOn/baseOn cleared. 
     */
    private static Model.Moxture cloneWithoutBasedOn(Model.Moxture src) {
        Model.Moxture c = new Model.Moxture();
        c.setName(src.getName());
        c.setMethod(src.getMethod());
        c.setEndpoint(src.getEndpoint());
        c.setHeaders(src.getHeaders()==null?null:new LinkedHashMap<>(src.getHeaders()));
        c.setVars(src.getVars() == null ? null : new LinkedHashMap<>(src.getVars()));
        c.setQuery(src.getQuery()==null?null:new LinkedHashMap<>(src.getQuery()));
        c.setPayload(src.getPayload()); // JSON nodes are fine to share for our usage
        c.setSave(src.getSave()==null?null:new LinkedHashMap<>(src.getSave()));
        c.setExpectedStatus(src.getExpectedStatus());
        c.setMoxtures(src.getMoxtures()==null?null:new ArrayList<>(src.getMoxtures()));
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

    /** 
     * Ensure a moxture is either a group OR a single call, not both. 
     */
    private static void validateMoxture(Model.Moxture f) {
        boolean isGroup = f.getMoxtures() != null;
        boolean isHttp  =
            (f.getEndpoint() != null && !f.getEndpoint().isBlank()) ||
            (f.getMethod()   != null && !f.getMethod().isBlank())   ||
            f.getPayload() != null ||
            f.getExpectedStatus() != null ||
            (f.getHeaders() != null && !f.getHeaders().isEmpty()) ||
            (f.getQuery()   != null && !f.getQuery().isEmpty())   ||
            (f.getSave()    != null && !f.getSave().isEmpty());
        if (isGroup && isHttp) {
            throw new IllegalStateException("Moxture '" + f.getName() + "' cannot define both 'moxtures' and HTTP fields");
        }
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static <K, V> Map<K, V> mergeMap(Map<K, V> parent, Map<K, V> child) {
        if ((parent == null || parent.isEmpty()) && (child == null || child.isEmpty())) {
            return child;
        }
        Map<K, V> out = new LinkedHashMap<>();
        if (parent != null) out.putAll(parent);
        if (child != null) out.putAll(child);
        return out;
    }


    /** 
     * If the node is textual and looks like JSON, parse it to a JSON tree; otherwise return as is. 
     */
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

    // =====================================================================
    //  Group helpers
    // =====================================================================

    private static boolean isGroupMoxture(Model.Moxture f) {
        // A group is any moxture that *declares* a moxtures list (even empty → no-op group)
        return f != null && f.getMoxtures() != null;
    }



    // =====================================================================
    // Initial vars loader
    // =====================================================================

    /**
     * Load a top-level {@code vars:} map from the hierarchical moxture files:
     * root → package ancestors → (optional) per-test-class directory.
     * <p>
     * The closest file to the test class completely overrides any higher-level vars
     * (no merging). If no file defines {@code vars}, returns an empty map.
     */
    @SuppressWarnings("unchecked")
    private Map<String,Object> loadHierarchicalVars(Class<?> testClass, IO.MoxtureConfig cfg, ObjectMapper yamlMapper) {
        List<String> candidates = repo.candidateAncestorPaths(testClass, cfg);
        Map<String,Object> last = null;

        ClassLoader tccl     = Thread.currentThread().getContextClassLoader();
        ClassLoader testCl   = testClass.getClassLoader();
        ClassLoader fallback = Moxter.class.getClassLoader();

        for (String cp : candidates) {
            URL url = (tccl != null ? tccl.getResource(cp) : null);
            if (url == null && testCl != null) url = testCl.getResource(cp);
            if (url == null && fallback != null) url = fallback.getResource(cp);
            if (url == null) continue;

            try (InputStream in = url.openStream()) {
                Model.MoxtureFile suite = yamlMapper.readValue(in, Model.MoxtureFile.class);
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



    // ############################################################################
    // ############################################################################
    // ############################################################################
    //
    // NESTED CLASSES (config/engine/io/runtime/util/model)
    //
    // ############################################################################
    // ############################################################################
    // ############################################################################


    /**
     * A fluent builder used to configure and execute a Moxture call.
     * 
     * <p>This class handles the "Preparation Phase" of a test step, allowing you to 
     * inject variables, set execution flags (like lax mode), and toggle debug logging 
     * before triggering the actual network request.</p>
     * 
     * <p><b>Example Usage:</b></p>
     * <pre>{@code
     * mx.caller()
     *   .lax(true)
     *   .withPrintReturn(true)
     *   .with("in_petName", "Rex")
     *   .call("create_pet_for_owner") // Triggers the call
     *   .assertVar("petId", id -> id.isNotNull());
     * }</pre>
     */
    public static class MoxterCaller 
    {
        // Reference to the root encompassing engine
        private final Moxter moxter;
        private boolean lax = false;
        private boolean jsonPathLax = false;
        private boolean printReturn = false;
        private Map<String, Object> overrides = new LinkedHashMap<>();

        /**
         * Use Moxter.caller() instead
         * 
         * @param moxter      The encompassing Engine.
         */
        protected MoxterCaller(Moxter moxter) {
            this.moxter = moxter;
        }

        /**
         * Toggles 'Lax' mode for this specific call.
         * 
         * When enabled, a non-2xx HTTP status code will not immediately throw an exception,
         * allowing you to perform assertions on error responses (e.g., 404 or 400).
         * 
         * @param lax true to suppress automatic exception throwing on API errors.
         * @return this {@link MoxterCaller} for chaining.
         */
        public MoxterCaller lax(boolean lax) {
            this.lax = lax;
            return this;
        }

        /**
         * Toggles 'Lax' mode for JsonPath extractions.
         * 
         * If true, failed JsonPath extractions will return null rather than throwing an exception.
         * 
         * @param val true to enable lax JsonPath evaluation.
         * @return this {@link MoxterCaller} for chaining.
         */
        public MoxterCaller withJsonPathLax(boolean val) {
            this.jsonPathLax = val;
            return this;
        }

        /**
         * Enables automatic pretty-printing of the response body to standard output.
         * Useful for debugging complex JSON structures during test development.
         * * @param val true to print the result after execution.
         * @return this {@link MoxterCaller} for chaining.
         */
        public MoxterCaller withPrintReturn(boolean val) {
            this.printReturn = val;
            return this;
        }

        /**
         * Injects a map of variables into the moxture call context. 
         * 
         * These overrides will be taken into account for placeholder interpolation
         * (e.g., {@code ${varName}} in the Moxture's YAML definition)
         * with precedence over: <br />
         * - variables defined inside the YAML moxture <br />
         * - variables in the Moxter global var context
         * 
         * 
         * @param overrides A map of keys and values placeholders.
         * @return this {@link MoxterCaller} for chaining.
         */
        public MoxterCaller with(Map<String, Object> overrides) {
            if (overrides != null) this.overrides.putAll(overrides);
            return this;
        }

        /**
         * Injects a single variable into the moxture call context.
         * 
         * @param key   The variable name (used as ${key} in YAML).
         * @param value The value to inject.
         * @return this {@link MoxterCaller} for chaining.
         */
        public MoxterCaller with(String key, Object value) {
            this.overrides.put(key, value);
            return this;
        }

        /**
         * Executes the moxture call based on the current configuration.
         * 
         * <p>This method performs the template merging, executes the HTTP request, 
         * saves variables defined in the 'save' block, and optionally prints the output.
         * 
         * @param moxtureName The name of the Moxture (YAML file) to execute.
         * @return A {@link MoxtureResult} containing the response and providing assertion methods.
         * @throws RuntimeException if execution fails and {@code lax} mode is false.
         */
        // TODO update javadoc
        /**
         * Base call method accomodating all features.
         * 
         * <p>Allows for a set of per-call variable overrides.
         * <p>The call-scoped javaOverrides map is applied only for the duration of this call:
         * <ul>
         *   <li><b>Reads:</b> when templating, header/query resolution, or payload
         *       substitution looks up a variable, the engine will check the call-scoped
         *       overrides first. If the key is not present there, it falls back to the
         *       engine’s global variables.</li>
         *   <li><b>Writes:</b> when a moxture specifies a "save:" block, the
         *       extracted values are always written into the engine’s global variables,
         *       never into the call-scoped overrides. This ensures saved IDs or tokens
         *       are available to subsequent moxtures and test code.</li>
         * </ul>
         *
         * <p>The overrides are ephemeral: once the call returns, they are discarded.
         * Global variables are never modified unless the moxture itself performs a save
         * operation or the test code calls {@link #varsPut(String, Object)} directly.
         *
         * <p>Examples:
         * <pre>{@code
         * // Global default
         * fx.varsPut("buyer", "Alice");
         *
         * // Call with temporary override (buyer = Bob)
         * Map<String,Object> scoped = Map.of("buyer", "Bob", "region", 3);
         * fx.callMoxture("create_order", scoped);
         *
         * // After the call:
         * //   - "buyer" in global vars is still "Alice"
         * //   - "region" is not present in global vars
         * //   - any saved vars (e.g., "orderId") are in global vars
         * }</pre>
         *
         *
         * @param moxtureName the moxture (or group) name
         * @param lax  if true, expected-status mismatches are logged (warning) and won't fail the test.
         *             For group moxtures, each child is executed in lax mode.
         * @param jsonPathLax if true, the library that reads JSON paths in the "save" in the yaml moxtures
         *             will have a lax (aka lenient) configuration.
         *             Meaning: If parent is null, asking for $.parent.child.value simply returns null.
         *             If you make a typo like $.parnet, it returns null (instead of crashing).
         * @param overrides per-call variable overrides (not mutated; keys shadow globals)
         * @return {@code null} for group moxtures; for single moxtures, the response envelope.
         */
        public MoxtureResult call(String moxtureName) 
        {
            Model.ResponseEnvelope env = callInternal(moxtureName, overrides);

            if (printReturn && env != null && env.body() != null) {
                System.out.println("[Moxter] Result for " + moxtureName + ":\n" + env.body().toPrettyString());
            }
            return new MoxtureResult(env, moxter, moxtureName);
        }


        /**
         * TODO 
         * @param moxtureName
         * @param overrides
         * @return
         */
        private Model.ResponseEnvelope callInternal(String moxtureName, Map<String, Object> overrides) 
        {
            Objects.requireNonNull(moxtureName, "name");
            MaterializedMoxture r = this.moxter.resolveByName(moxtureName);

            // 1. Build this moxture's local overrides.
            // As you requested: The moxture's own YAML vars yield to the passed-in overrides.
            // (If this is a child in a group, callScopedOverrides contains the group's vars)
            Map<String, Object> localOverrides = new LinkedHashMap<>();
            
            // Local vars provided by the moxture (or the group of moxtures):
            if (r.moxt.getVars() != null) {
                localOverrides.putAll(r.moxt.getVars());
            }
            // Add vars provided by the call or by the previous step (in the case of a group moxture)
            // These take precedence over local vars.
            if (overrides != null) {
                localOverrides.putAll(overrides);
            }

            // 2. Call
            validateMoxture(r.moxt);

            // 2.1. If group Moxture
            if (isGroupMoxture(r.moxt)) 
            {   final String label = "group '" + moxtureName + "'" + (lax ? " (lax)" : "");
                
                // We pass THIS group's successfully merged localScope down to its children.
                // To the children, these act as their "callScopedOverrides".
                for (String childMoxtureName : r.moxt.getMoxtures()) 
                {   try 
                    {   // Recurse
                        callInternal(childMoxtureName, localOverrides);
                    } 
                    catch (Throwable t) 
                    {   if (lax) {
                            log.warn("[Moxter] (lax) {} → child '{}' failed — skipping. Cause: {}", label, childMoxtureName, t.toString());
                        } else {
                            if (t instanceof RuntimeException) throw (RuntimeException) t;
                            throw new RuntimeException("Error executing moxture '" + childMoxtureName + "' in " + label, t);
                        }
                    }
                }
                return null;
            }
            // 2.2. Else: single moxture
            else
            {   // Create the Layered View (Local -> Global) using the fluent facade for writes
                Map<String, Object> mergedVars = new Runtime.CallScopedVars(localOverrides, this.moxter.vars, moxter.vars()::put);
                try 
                {   return this.moxter.executor.execute(r.moxt, r.baseDir, mergedVars, lax, jsonPathLax);
                } 
                catch (Throwable t) 
                {   if (lax) {
                        log.warn("[Moxter] (lax) single moxture '{}' failed — skipping. Cause: {}", moxtureName, t.toString());
                        return null;
                    }
                    if (t instanceof RuntimeException) throw (RuntimeException) t;
                    throw new RuntimeException("Error executing moxture '" + moxtureName + "'", t);
                }
            }
        }

    }


    // ############################################################################

    /**
     * A fluent facade for managing Moxter's globally-scoped OR moxture-locally-scoped, variables.
     * 
     * <p>Variables stored in Moxter's globally-scoped variables map are available to all 
     * moxture calls within the same engine instance. They can be referenced in 
     * YAML moxtures definitions using placeholder syntax (e.g., {@code {{varName}}}).
     * 
     * <p><b>Example Usage:</b>
     * <pre>{@code
     *      // Write a variable
     *      mx.vars().put("ownerName", "Alice");
     *     // Read a variable with automatic type conversion
     *     Long orderId = mx.vars().read("ownerId").asLong();
     *     // Check existence
     *     if (mx.vars().has("token")) { 
     *        mx.vars().clear();
     * }</pre>
     * 
     * <p>Variables stored in a moxture's locally-scoped variables are only available
     * inside that specific YAML moxture definition.
     * 
     * <p><b>Example Usage:</b>
     * <pre>{@code
     *      // Write a variable
     *      mx.vars("create_pet").put("petName", "Snowy");
     * }</pre>
     */
    public static class MoxterVars 
    {
        private final Moxter moxter;

        // If null then the scope is global
        private final String moxtureName;

        // Cached map of the target(local/global) variables
        private final Map<String, Object> targetMap; 

        protected MoxterVars(Moxter moxter, String moxtureName) {
            this.moxter = moxter;
            this.moxtureName = moxtureName;
            this.targetMap = resolveTargetMap();
        }

        private boolean isScopeGlobal()
        {   return moxtureName == null;
        }

        /**
         * Resolves the vars according to what scope we should be looking at (global/local to a moxture)
         * 
         * Returns an empty map if local vars are missing to avoid NullPointerExceptions.
         */
        private Map<String, Object> resolveTargetMap() 
        {
            if (isScopeGlobal()) { 
                return moxter.vars; 
            }

            // Local Scope
            try {
                MaterializedMoxture resolved = moxter.resolveByName(moxtureName);
                Map<String, Object> localVars = resolved.moxt.getVars();
                return (localVars != null) ? localVars : Collections.emptyMap();
            } catch (Exception e) {
                // If the moxture name doesn't exist yet, return empty 
                // so that .get() returns null rather than crashing the test.
                return Collections.emptyMap();
            }
        }


        /**
         * Clears (empties) all variables currently stored in Moxter's variables context.
         */
        public void clear() { 
            targetMap.clear(); 
        }

        /**
         * Checks if a variable is present in Moxter's variables context.
         * 
         * <p> Warning: if operating in a moxture local scope, will not check the global scope.
         * 
         * @param key The variable name
         * @return true if the variable exists, false otherwise
         */
        public boolean has(String key) {
            return targetMap.containsKey(key);
        }

        /**
         * Puts a variable into Moxter's variables context.
         *
         * <ul>
         * <li><b>Strict mode enabled:</b> throws an exception if the key already exists.</li>
         * <li><b>Strict mode disabled:</b> overwrites and logs a WARN if there was a previous value.</li>
         * </ul>
         *
         * <p>Note: If {@code varsOverwriteStrict} is enabled, {@code put()} will throw 
         * an exception if the key exists in <b>either</b> scope (local/global) to prevent naming 
         * collisions during the moxture execution.
         * 
         * @param key   The variable name
         * @param value The value to store
         * @return the previous value associated with key, or {@code null} if there was none
         * @throws IllegalStateException if strict mode is enabled and the key already exists
         */
        public Object put(String key, Object value) {
            requireValidKey(key);

            // 1. Strict Mode Validation
            if (   moxter.varsOverwriteStrict)
            {   
                // Check if it exists ANYWHERE in the visible stack
                // (local scope and  global scope end up being merged upon moxture call)
                if (   moxter.vars.containsKey(key)                    // NOSONAR
                    || targetMap.containsKey(key) 
                   ) 
                {   throw new IllegalStateException("Var '" + key + "' already exists. Strict mode being enabled, this is not allowed.");
                }
            }

            // 2. Execution of the Put
            // We always put into the CURRENT targetMap. 
            // If we are in mx.vars(), it goes to Global.
            // If we are in mx.vars("moxture_name"), it goes to that moxture local scope.
            Object prev = targetMap.put(key, value);

            if (!moxter.varsOverwriteStrict && prev != null) 
            {   log.warn("[Moxter] Overwriting var '{}' in {} scope. (old={}, new={})",
                          key, 
                          isScopeGlobal() ? "GLOBAL" : "LOCAL (" + moxtureName + ")",
                          prev,
                          value);
            }
            return prev;
        }

        /**
         * Puts a variable into the current context only if it is absent in the 
         * entire visible stack (Global + Local).
         *
         * @param key   The variable name
         * @param value The value to store
         * @return true if the value was set; false if the key already exists in 
         * either the local or global scope.
         */
        public boolean putIfAbsent(String key, Object value) {
            requireValidKey(key);

            // Check the entire stack to prevent shadowing or overwriting
            if (moxter.vars.containsKey(key) || targetMap.containsKey(key)) {
                return false;
            }

            // We put into the targetMap (either the Global handle or the Local handle)
            return targetMap.put(key, value) == null;
        }

        /**
         * Retrieves a variable from Moxter's variables context.
         * 
         * @param key The variable name
         * @return The stored value
         * @throws IllegalStateException if the variable does not exist
         */
        public VarAccessor read(String key) {
            if (!targetMap.containsKey(key)) {
                throw new IllegalStateException("Var '" + key + "' does not exist");
            }
            return new VarAccessor(key, targetMap.get(key));
        }

        /**
         * Standard Map-style retrieval of a raw variable value.
         * 
         * <p>Use this when you need the raw {@code Object} for manual casting or 
         * passing directly into other methods.
         * 
         * @param key The variable name
         * @return The raw value, or {@code null} if the variable does not exist
         */
        public Object get(String key) {
            return targetMap.get(key);
        }

        /**
         * Returns a live, unmodifiable view of the current Moxter variables context map.
         */
        public Map<String, Object> view() {
            return Collections.unmodifiableMap(targetMap);
        }

        /**
         * Returns the current Moxter variables context map as a pretty-printed JSON string.
         * 
         * <p>Intended for debugging and test logging only.
         */
        public String dump() {
            try { return VARS_DUMP_MAPPER.writeValueAsString(targetMap); } 
            catch (JsonProcessingException e) {
                log.error("Failed to dump vars", e);
                return moxter.vars.toString();
            }
        }

        private void requireValidKey(String key) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Key is null/blank");
            }
        }


        // ========================================================
        // NESTED ACCESSOR
        // ========================================================
        /**
         * A fluent accessor for a dynamically typed Moxter variable.
         * 
         * <p>Provides safe casting and parsing methods to retrieve the 
         * variable in the desired format.
         */
        public static class VarAccessor 
        {
            private final String key;
            private final Object val;

            protected VarAccessor(String key, Object val) {
                this.key = key;
                this.val = val;
            }

            /** Returns true if the underlying value is null. */
            public boolean isNull() { return val == null; }
            
            /** Returns the raw, uncast Object. */
            public Object asObject() { return val; }

            /** Casts the variable to the specified type. */
            public <T> T asType(Class<T> type) {
                if (val == null) return null;
                try { return type.cast(val); } 
                catch (ClassCastException e) {
                    throw new IllegalStateException(
                        "Var '" + key + "' is not of type " + type.getSimpleName() +
                        " (actual: " + val.getClass().getSimpleName() + ")", e
                    );
                }
            }

            /** Returns the variable as a String. */
            public String asString() {
                if (val instanceof String s) {
                    return s;
                }
                return val == null ? null : String.valueOf(val);
            }

            /** * Returns the variable as a Long, handling Integer/String conversions.
             * @throws IllegalStateException if the variable cannot be parsed as a number
             */
            public Long asLong() {
                if (val == null) return null;
                if (val instanceof Number) return ((Number) val).longValue();
                if (val instanceof String) {
                    try { return Long.parseLong((String) val); } 
                    catch (NumberFormatException e) {
                        throw new IllegalStateException("Var '" + key + "' cannot be parsed as Long: " + val, e);
                    }
                }
                throw new IllegalStateException("Var '" + key + "' is not a number (actual: " + val.getClass().getSimpleName() + ")");
            }

            /** Parses the variable into an {@link Instant} (supports ISO-8601). */
            public Instant asInstant() {
                String s = asString();
                if (s == null) return null;
                try { return Instant.parse(s); } 
                catch (DateTimeParseException e1) {
                    try { return ZonedDateTime.parse(s).toInstant(); } 
                    catch (DateTimeParseException e2) { return OffsetDateTime.parse(s).toInstant(); }
                }
            }

            /** Parses the variable into an {@link Instant} using a custom formatter. */
            public Instant asInstant(DateTimeFormatter formatter) {
                String s = asString();
                if (s == null) return null;
                return formatter.parse(s, Instant::from);
            }

            /** * Casts the variable to a typed List, handling numeric conversions automatically.
             */
            public <T> List<T> asList(Class<T> elementType) {
                if (val == null) return null;
                if (!(val instanceof List)) {
                    throw new IllegalStateException("Var '" + key + "' is not a List. Actual type: " + val.getClass().getName());
                }

                List<?> rawList = (List<?>) val;
                List<T> result = new ArrayList<>(rawList.size());

                for (int i = 0; i < rawList.size(); i++) {
                    Object item = rawList.get(i);
                    if (item == null) {
                        result.add(null);
                        continue;
                    }
                    if (Number.class.isAssignableFrom(elementType) && item instanceof Number) {
                        Number num = (Number) item;
                        if (elementType == Long.class) result.add(elementType.cast(num.longValue()));
                        else if (elementType == Integer.class) result.add(elementType.cast(num.intValue()));
                        else if (elementType == Double.class) result.add(elementType.cast(num.doubleValue()));
                        else result.add(elementType.cast(item)); 
                    } else {
                        try { result.add(elementType.cast(item)); } 
                        catch (ClassCastException e) {
                            throw new IllegalStateException(String.format("Element at index %d in var '%s' is not %s", i, key, elementType.getSimpleName()), e);
                        }
                    }
                }
                return result;
            }
        }



    }

    // ############################################################################

    /**
     * Represents the outcome of a moxture execution.
     * Provides a fluent interface for inspecting the response and extracting data.
     */
    public static class MoxtureResult 
    {
        private final Model.ResponseEnvelope envelope;
        private final String moxtureName;
        // Reference to the encompassing engine
        private final Moxter moxter;

        /**
         * @param envelope    The response wrapper containing status, body, and raw content.
         * @param moxtureName The name of the moxture that produced this result (for error context).
         * @param moxter      The encompassing Engine.
         */
        public MoxtureResult(Model.ResponseEnvelope envelope, Moxter moxter, String moxtureName) {
            this.envelope    = envelope;
            this.moxter      = moxter;
            this.moxtureName = moxtureName;
        }

        /**
         * Returns the response body parsed as a Jackson {@link JsonNode}.
         * Useful for manual assertions or complex inspections.
         */
        public JsonNode getBody() {
            return (envelope != null) ? envelope.body() : null;
        }

        /**
         * Returns the HTTP status code of the response.
         */
        public int getStatus() {
            return (envelope != null) ? envelope.status() : -1;
        }

        /**
         * Returns the raw string representation of the response body.
         */
        public String getRawBody() {
            return (envelope != null) ? envelope.raw() : null;
        }

        /**
         * Access to the full envelope including headers.
         */
        public Model.ResponseEnvelope getEnvelope() {
            return envelope;
        }

        /**
         * Extracts a raw value from the response body using a JsonPath expression.
         * 
         * <p>This is a terminal method used to retrieve data from the response that 
         * hasn't necessarily been saved in the Moxter variable context.
         * 
         * <p><b>Example Usage:</b>
         * <pre>{@code
         * String firstTag = (String) fx.call("get_pet")
         *                              .returnJPath("$.tags[0].name");
         * }</pre>
         * 
         * @param jsonPath The JsonPath expression to evaluate (e.g., "$.id" or "$.items[0].name").
         * @return The extracted value (String, Number, Map, List, etc.), or {@code null} if no response exists.
         * @throws RuntimeException if the path is invalid or cannot be found in the JSON.
         * @see #assertVar(String) For performing fluent assertions on saved variables.
         */
        public Object returnJPath(String jsonPath) {
            if (envelope == null) {
                return null;
            }
            return Utils.Json.extract(envelope.raw(), jsonPath, moxtureName);
        }

        /**
         * Convenience (and terminating) method to extract '$.id' from the response as a long.
         * 
         * <p>Useful for setup phases where only the ID is needed for subsequent logic.
         * 
         * <p><b>Example Usage:</b>
         * <pre>{@code
         * Long petId = mx.call("create_pet")
         *                .returnId();
         * // do stuff with the id
         * }</pre>
         * 
         * @return The ID extracted from the JSON response
         * @throws IllegalStateException if $.id is missing or not numeric
         */
        public long returnId() {
            Object v = returnJPath("$.id");
            if (v instanceof Number n) return n.longValue();
            if (v instanceof String s) {
                try { return Long.parseLong(s); }
                catch (NumberFormatException ex) { 
                    throw new IllegalStateException("Value at '$.id' for moxture '" + moxtureName + "' is not a number: " + v, ex); 
                }
            }
            throw new IllegalStateException("Value at '$.id' for moxture '" + moxtureName + "' is not numeric: " + v);
        }

        /** 
         * Entry point for performing fluent assertions on a specific JSON path using AssertJ.
         * 
         * <p>This method extracts the value at the given {@code jsonPath} and wraps it in an 
         * AssertJ {@link ObjectAssert}. This allows for complex, readable assertions 
         * without leaving the fluent chain.
         * 
         * <p><b>Example Usage:</b>
         * <pre>{@code
         * mx.call("get_pet")
         *   .assertThat("$.name").isEqualTo("Snowy");
         * }</pre>
         *
         * <p> Notice: This method does not permit assertion chaining. To achieve this, save the 
         * MoxtureResult and operate multiple assertion calls without chaining.
         * 
         * <p>
         * Note: This method uses the {@code .as()} description in AssertJ. If the assertion fails, 
         * the error message will automatically include the moxture name and the path 
         * (e.g., "[Moxture 'get_pet' at path '$.name'] expected...").
         * </p>
         *
         * @param jsonPath The JsonPath expression to extract from the response body.
         * @return An AssertJ {@link ObjectAssert} to continue the assertion chain.
         * @throws RuntimeException if the path extraction fails.
         */
        public ObjectAssert<Object> assertThat(String jsonPath) {
            Object actual = returnJPath(jsonPath);
            // We use Assertions.assertThat(actual) to give the user 
            // the full power of AssertJ immediately.
            return Assertions.assertThat(actual)
                             .as("Moxture '%s' at path '%s'", moxtureName, jsonPath);
        }

        /**
         * Entry point for performing fluent assertions on a variable previously 
         * saved by the moxture's YAML definition.
         * 
         * <p>This is the preferred way to verify data. By using the variable name defined 
         * in the {@code save:} section of your YAML, you avoid hardcoding JsonPaths 
         * in your Java tests.
         * 
         * <p><b>Example YAML:</b>
         * <pre>{@code
         * save:
         * petId: "$.id"
         * petName: "$.name"
         * }</pre>
         * 
         * <p><b>Example Usage:</b>
         * <pre>{@code
         * result = mx.call("create_pet")
         * result.assertVar("petId").isNotNull();
         * result.assertVar("petName").isEqualTo("Snowy");
         * }</pre>
         * 
         * <p> Notice: This method does not permit assertion chaining. To achieve this, 
         * check out {@link assertVar}.
         *
         * @param varName The name of the variable from the Moxter var context
         *                (possibly coming from the moxture 'save' block, or not)
         * @return An AssertJ {@link ObjectAssert} for the saved value.
         * @see #assertThat(String) For one-off extractions using raw JsonPaths.
         */
        public ObjectAssert<Object> assertVar(String varName) {
            Object actual = moxter.varsGet(varName);
            return org.assertj.core.api.Assertions.assertThat(actual)
                    .as("Saved variable '%s' from moxture '%s'", varName, moxtureName);
        }

        /**
         * Performs fluent assertions on a saved variable using a consumer, 
         * returning this {@link MoxtureResult} to allow for further chaining.
         * 
         * <p>This is the "infinite chain" version of variable assertion. It keeps 
         * the developer in the context of the result rather than the context of 
         * AssertJ, making it ideal for checking multiple values in a single statement.
         * 
         * <p><b>Example Usage:</b>
         * <pre>{@code
         * mx.call("create_pet")
         *   .assertVar("petId",   id -> id.isNotNull().isInstanceOf(Long.class))
         *   .assertVar("petName", name -> name.isEqualTo("Snowy"))
         *   .assertVar("status",  s -> s.isIn("AVAILABLE", "PENDING"));
         * }</pre>
         *
         * @param varName      The name of the variable to fetch from the Moxter context.
         * @param requirements A consumer that defines the AssertJ requirements for the value.
         * @return This {@link MoxtureResult} for continued chaining.
         * @see #assertVar(String) To break the chain and use AssertJ methods directly.
         */
        public MoxtureResult assertVar(String varName, Consumer<ObjectAssert<Object>> requirements) 
        {
            requirements.accept(assertVar(varName));
            return this;
        }
    }


    // ############################################################################

    /**
     * 
     */
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

        public Moxter build() {
            return new Moxter(testClass, mockMvc, authSupplier);
        }
    }


    // ############################################################################

    /** 
     * Key for memorizing materialized moxtures by scope (baseDir) and name. 
     */
    private static final class MaterializedKey 
    {
        final String baseDir; // classpath dir where the moxture is defined
        final String name;
        MaterializedKey(String baseDir, String name) { this.baseDir = baseDir; this.name = name; }
        public boolean equals(Object o){ if(this==o)return true; if(!(o instanceof MaterializedKey))return false;
            MaterializedKey k=(MaterializedKey)o; return Objects.equals(baseDir,k.baseDir)&&Objects.equals(name,k.name); }
        public int hashCode(){ return Objects.hash(baseDir,name); }
        public String toString(){ return baseDir+":"+name; }
    }


    // ############################################################################

    /**
     * An internal container representing a fully prepped, executable moxture.
     *
     * <p>This class pairs a completely materialized moxture definition (where all 
     * {@code basedOn} inheritance and hierarchical merging have been flattened) 
     * with the physical classpath directory where it was discovered.
     *
     * <p>Keeping track of the {@code baseDir} is critical during the execution phase, 
     * as it acts as the anchor point for resolving relative file imports (e.g., when 
     * a payload or multipart file is defined as {@code "classpath:request.json"}).
     */
    private static final class MaterializedMoxture 
    {
        /**
         * The fully materialized moxture definition (with all 'basedOn' inheritance 
         * deeply merged). 
         */
        final Model.Moxture moxt;

        /** 
         * The classpath directory where this moxture was discovered. 
         * Used as the anchor point for resolving relative file imports.
         */
        final String baseDir;

        MaterializedMoxture(Model.Moxture moxt, String baseDir) 
        { this.moxt = moxt; this.baseDir = baseDir; }
    }


    // ############################################################################

    /** 
     * Loading moxtures from classpath. 
     */
    static final class IO 
    {
        /** Immutable configuration used for locating moxtures on classpath. */
        static final class MoxtureConfig {
            final String rootPath;                // e.g., "integrationtests2/moxtures"
            final boolean perTestClassDirectory;  // true => add "/{TestClassName}"
            final String fileName;                // "moxtures.yaml" (includes extension)

            MoxtureConfig(String rootPath, boolean perTestClassDirectory, String fileName) {
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


        interface MoxtureRepository {
            final class LoadedSuite {
                public final Model.MoxtureFile suite;
                public final String baseDir; // classpath folder where the moxtures file lives
                public LoadedSuite(Model.MoxtureFile suite, String baseDir) { this.suite = suite; this.baseDir = baseDir; }
            }
            LoadedSuite loadFor(Class<?> testClass, IO.MoxtureConfig cfg);
        }

        /**
         * Classpath repository:
         * - Build exact closest path: rootPath + "/" + {package as folders} + ["/{TestClassName}"] + "/" + fileName
         * - Try TCCL, fall back to test class CL, then this class CL.
         * - If not found, throw with a clear message.
         * - Provides hierarchical name lookup helpers.
         */
        static final class ClasspathMoxtureRepository implements MoxtureRepository {
            private final ObjectMapper mapper;

            ClasspathMoxtureRepository(ObjectMapper mapper) { this.mapper = mapper; }

            public LoadedSuite loadFor(Class<?> testClass, IO.MoxtureConfig cfg) {
                final String classpath = buildClosestClasspath(testClass, cfg);
                final String displayPath = "classpath:/" + classpath;

                URL url = firstNonNullUrl(
                        Thread.currentThread().getContextClassLoader(),
                        testClass.getClassLoader(),
                        Moxter.class.getClassLoader(),
                        classpath
                );

                if (url == null) {
                    throw new IllegalStateException(
                        "[Moxter] No moxtures file found for " + testClass.getName() + "\n" +
                        "Expected at: " + displayPath + "\n" +
                        "Hint: place the file under src/test/resources/" + classpath
                    );
                }

                if (isDebug()) System.out.println("[Moxter] Loading " + displayPath + " -> " + url);

                try (InputStream in = url.openStream()) {
                    Model.MoxtureFile suite = mapper.readValue(in, Model.MoxtureFile.class); // YAML mapper parses JSON too
                    String baseDir = parentDirOf(classpath);
                    return new LoadedSuite(suite, baseDir);
                } catch (IOException e) {
                    throw new RuntimeException("Failed loading moxtures: " + displayPath, e);
                }
            }

            /* ===== Hierarchical lookup (helpers) ===== */

            /** Returns candidate ancestor classpaths from closest → root (inclusive). */
            List<String> candidateAncestorPaths(Class<?> testClass, IO.MoxtureConfig cfg) {
                List<String> out = new ArrayList<>();
                String pkg = (testClass.getPackageName() == null ? "" : testClass.getPackageName().replace('.', '/'));

                // 1) If per-class dir is enabled, add the closest file first
                if (cfg.perTestClassDirectory) {
                    out.add(cfg.rootPath + (pkg.isEmpty() ? "" : "/" + pkg) + "/" + testClass.getSimpleName() + "/" + cfg.fileName);
                }

                // 2) Then each package ancestor level: root/pkg/.../moxtures.yaml → ... → root/moxtures.yaml
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

            /** Finds the first (closest) occurrence of a moxture by name across ancestor files (starting from test package). */
            Resolved findFirstByName(Class<?> testClass, IO.MoxtureConfig cfg, String name, ObjectMapper yamlMapper) {
                List<String> candidates = candidateAncestorPaths(testClass, cfg);
                ClassLoader tccl = Thread.currentThread().getContextClassLoader();
                ClassLoader fallback = Moxter.class.getClassLoader();
                for (String cp : candidates) {
                    URL url = firstNonNullUrl(tccl, testClass.getClassLoader(), fallback, cp);
                    if (url == null) continue;
                    try (InputStream in = url.openStream()) {
                        Model.MoxtureFile raw = yamlMapper.readValue(in, Model.MoxtureFile.class);
                        if (raw.moxtures() == null || raw.moxtures().isEmpty()) continue;

                        // NOTE: do NOT materialize here; scan raw and return the raw hit.
                        for (Model.Moxture f : raw.moxtures()) {
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
            List<String> candidateAncestorPathsFromBaseDir(String baseDir, IO.MoxtureConfig cfg) {
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

            /** Finds the first (closest) occurrence of a moxture by name, starting from an arbitrary baseDir. */
            Resolved findFirstByNameFromBaseDir(Class<?> testClass, IO.MoxtureConfig cfg,
                                                String startBaseDir, String name, ObjectMapper yamlMapper) {
                List<String> candidates = candidateAncestorPathsFromBaseDir(startBaseDir, cfg);
                ClassLoader tccl = Thread.currentThread().getContextClassLoader();
                ClassLoader fallback = Moxter.class.getClassLoader();
                for (String cp : candidates) {
                    URL url = firstNonNullUrl(tccl, testClass.getClassLoader(), fallback, cp);
                    if (url == null) continue;
                    try (InputStream in = url.openStream()) {
                        Model.MoxtureFile raw = yamlMapper.readValue(in, Model.MoxtureFile.class);
                        if (raw.moxtures() == null || raw.moxtures().isEmpty()) continue;

                        for (Model.Moxture f : raw.moxtures()) {
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
                final Model.Moxture call;
                final String baseDir;
                final String displayPath;
                Resolved(Model.Moxture call, String baseDir, String displayPath) {
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

            private static String buildClosestClasspath(Class<?> testClass, IO.MoxtureConfig cfg) {
                String pkg = (testClass.getPackageName() == null ? "" : testClass.getPackageName().replace('.', '/'));
                String classDir = cfg.perTestClassDirectory ? ("/" + testClass.getSimpleName()) : "";
                String base = (pkg.isEmpty() ? cfg.rootPath : (cfg.rootPath + "/" + pkg)) + classDir + "/";
                String full = base + cfg.fileName; // EXACT file name (e.g., "moxtures.yaml")
                if (isDebug()) System.out.println("[Moxter] Expecting moxtures at: classpath:/" + full + " for " + testClass.getName());
                return full;
            }

            private static String parentDirOf(String path) {
                int i = path.lastIndexOf('/');
                return (i > 0) ? path.substring(0, i) : "";
            }
            private static boolean isDebug() {
                return "true".equalsIgnoreCase(System.getProperty("Moxter.debug", "false"));
            }
        }
    }


    // ############################################################################

    /** 
     * Runtime: templating, payloads, status matching, http execution. 
     */
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

        /**
         * 
         */
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
                        log.warn("[Moxter] Interpolation warning: Variable '{{}}' found in payload " +
                                 "but missing from vars! (Leaving as-is). This will probably cause the " +
                                 "moxture cal to fail.", key);

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
                    ClassLoader fallback = Moxter.class.getClassLoader();
                    if (fallback != null) url = fallback.getResource(path);
                }

                if (url == null) throw new IllegalArgumentException("Resource not found: " + rawPath);

                try (InputStream in = url.openStream()) {
                    return in.readAllBytes();
                } catch (IOException e) {
                    throw new RuntimeException("Failed reading bytes from " + path, e);
                }
            }


            private JsonNode loadClasspathPayload(String baseDir, String rawPath) throws IOException {
                // Relative to the moxtures file directory unless absolute "/..."
                String path = rawPath.startsWith("/") ? rawPath.substring(1) : (baseDir + "/" + rawPath);

                URL url = null;
                ClassLoader tccl = Thread.currentThread().getContextClassLoader();
                if (tccl != null) url = tccl.getResource(path);
                if (url == null) {
                    ClassLoader fallback = Moxter.class.getClassLoader();
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



        /**
         * Map wrapper that represents the combination of:
         * <ul>
         *   <li><b>Per-call-scoped variable overrides</b> (provided by the caller)</li>
         *   <li><b>Global engine variables</b> (owned by Moxter)</li>
         * </ul>
         *
         * <p>Semantics:
         * <ul>
         *   <li><b>Reads (get/containsKey):</b> first consult the scoped overrides;
         *       if the key is not present there, fall back to the global vars.</li>
         *   <li><b>Iteration (entrySet/size):</b> presents a merged snapshot view,
         *       with scoped overrides taking precedence on key collisions.</li>
         *   <li><b>Writes (put):</b> always delegated to the engine’s global
         *       writing mecanism (via the provided writer) thus using a single 
         *       "source of truth" which exerts overwrite-warning semantics.</li>
         *   <li><b>Overrides are immutable:</b> the scoped map is copied on construction
         *       and never mutated by this wrapper.</li>
         * </ul>
         *
         * <p>Why not just merge the locally scoped variables to the globals vars into a 
         * temporary map and use that as the call var-context (e.g., new HashMap(globals).putAll(scoped))?
         * <p>Unlike a static merged snapshot, this wrapper provides live read-through 
         * and write-through semantics. By maintaining a reference to the global engine variables
         * rather than a copy, it ensures that any global state changes occurring during a 
         * moxture's execution, such as an ID being saved by a preceding step in a group,
         * are immediately visible to the current scope. Furthermore, the delegated put operation
         * ensures that extracted values (the "Reality") are persisted to the engine's global state 
         * for use by future moxtures, while the immutable scoped map preserves the caller's 
         * "Intent" without pollution.
         * 
         * <p>This class is used internally by
         * {@link Moxter#callMoxture(String, Map)} to implement call-scoped
         * variable overrides. Test code does not normally need to use it directly.
         */
        private static final class CallScopedVars extends AbstractMap<String,Object> {
            private final Map<String,Object> scoped;    // per-call overrides (immutable snapshot is fine)
            private final Map<String,Object> globals;   // underlying engine vars (for reads)
            private final BiFunction<String,Object,Object> writer; // e.g., Moxter::varsPut

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
            Model.ResponseEnvelope execute(Model.Moxture spec, String baseDir, Map<String,Object> vars) {
                return execute(spec, baseDir, vars, false, false);
            }

            /**
             * Execute a single moxture call.
             * Logs a concise start line, rich DEBUG details, response preview, a finish line with duration,
             * and a compact warning when the expected status does not match.
             *
             * @param spec
             * @param baseDir
             * @param vars
             * @param lax do not fail on expected-status mismatches (still throws for infra errors)
             * @param jsonPathLax see callMoxture(...)
             */
            Model.ResponseEnvelope execute(Model.Moxture spec, String baseDir, 
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
                        throw new IllegalArgumentException("Moxture '" + name + "' has no 'endpoint' after basedOn resolution. " +
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
                    log.info("[Moxter] >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
                    log.info("[Moxter] >>> Executing moxture:  [{}, {}, {}]", name, method, uri);
                    if (log.isDebugEnabled()) {
                        log.debug("[Moxter] more info: expected={} headers={} query={} vars={} payload={}",
                                expectedStatusPreview(spec.getExpectedStatus()),
                                Utils.Logging.previewHeaders(headers0),
                                (query == null || query.isEmpty() ? "{}" : query.toString()),
                                Utils.Logging.previewVars(vars),
                                Utils.Logging.previewNode(payloadNode));

                        //#log.debug("[Moxter] spec=\n{}", jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec));
                    }

                    // 4) Build request
                    MockHttpServletRequestBuilder req;
                    Map<String,String> headers = (headers0 == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(headers0);
                    
                    // --- IF MULTIPART ---
                    if (spec.getMultipart() != null && !spec.getMultipart().isEmpty()) 
                    {   log.debug("[Moxter] Multipart detected");
                        // Remove explicit Content-Type header from the moxture definition.
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
                    {   log.debug("[Moxter] Standard, NOT Multipart");
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
                        log.debug("[Moxter] using Authentication principal={}", safeName(auth));
                    }
                    if (requiresCsrf(method)) {
                        req.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf());
                        log.debug("[Moxter] CSRF token added for {}", method);
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
                            log.debug("[Moxter] Non-JSON body (ct='{}') could not be parsed as JSON: {}",
                                    ctHeader, rootMessage(parseEx));
                        }
                    } else if (hasBody && log.isTraceEnabled()) {
                        log.trace("[Moxter] Skipping JSON parse for non-JSON response (ct='{}', sample='{}')",
                                ctHeader, Utils.Logging.truncate(raw, 80));
                    }

                    Model.ResponseEnvelope env = new Model.ResponseEnvelope(mvcResp.getStatus(), copyHeaders(mvcResp), body, raw);

                    // 5.5) DEBUG response preview
                    if (log.isDebugEnabled()) {
                        log.debug("[Moxter] response preview: status={} headers={} body={}",
                                env.status(),
                                Utils.Logging.previewRespHeaders(env.headers()),
                                Utils.Logging.previewNode(body));
                    }

                    // 6) Expected status (flexible & optional)
                    log.debug("FHI: examining Expected status ");
                    if (!statusMatcher.matches(spec.getExpectedStatus(), env.status()))
                    {   log.debug("FHI Expected status is NOT as expected");
                        String bodyPreview = (raw == null || raw.isBlank()) ? "<empty>" : Utils.Logging.truncate(raw, 500);
                        final String message = String.format(
                            Locale.ROOT,
                            "Unexpected HTTP %d for '%s' %s %s, expected=%s. Body=%s",
                            env.status(), name, method, uri, expectedStatusPreview(spec.getExpectedStatus()), bodyPreview
                        );
                        if (lax) {
                            log.info("[Moxter] Unexpected return status, but authorized in lax mode, so OK! : {}", message);
                        } else {
                            log.warn("[Moxter] {}", message);
                            throw new AssertionError(message);
                        }
                    }

                    // 7) Finish line + duration
                    final long tookMs = (System.nanoTime() - t0) / 1_000_000L;
                    log.info("[Moxter] <<< Finished executing moxture: [{}, {}, {}] with status: [{}], in {} ms",
                            name, method, uri, env.status(), tookMs);
                    log.info("[Moxter] <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<");

                    // === 8) Save variables only if JSON ===
                    if (spec.getSave() != null && !spec.getSave().isEmpty()) {
                        if (env.body() != null) {
                            DocumentContext ctx = JsonPath.using(jsonPathConfig).parse(raw);
                            for (Map.Entry<String, String> e : spec.getSave().entrySet()) {
                                // NOTE: initial vars are loaded at construction; runtime 'save' still writes directly.
                                // If you later want to enforce strictness here, route via varsPut / varsPutIfAbsent.
                                vars.put(e.getKey(), ctx.read(e.getValue()));
                            }
                            log.debug("[Moxter]    saved vars from '{}': {}", name, spec.getSave().keySet());
                        } else if (log.isDebugEnabled()) {
                            log.debug("[Moxter]    save skipped for '{}': response is not JSON", name);
                        }
                    }

                    if (log.isTraceEnabled()) log.trace("[Moxter] Raw body (len={}): {}", raw == null ? 0 : raw.length(), Utils.Logging.truncate(raw, 4000));
                    return env;

                } catch (RuntimeException re) {
                    log.warn("[Moxter] X [{}] {} failed: {}", method, name, rootMessage(re));
                    throw re;
                } catch (Exception e) {
                    // NOTE: Non-JSON bodies no longer cause JsonParseException to escape here.
                    log.warn("[Moxter] X [{}] {} errored: {}", method, name, rootMessage(e));
                    throw new RuntimeException("Error executing moxture '" + name + "'", e);
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
                return switch (m) {
                    case "GET"     -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(uri);
                    case "POST"    -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(uri);
                    case "PUT"     -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(uri);
                    case "PATCH"   -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(uri);
                    case "DELETE"  -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(uri);
                    case "HEAD"    -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head(uri);
                    case "OPTIONS" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(uri);
                    default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
                };
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



    // ############################################################################
    
    /** 
     * Small utilities (logging helpers). 
     */
    static final class Utils 
    {

        public static class Json 
        {
            /**
             * Extracts a value from a JSON string using a JsonPath expression.
             * <p>
             * This is the engine's primary extraction utility. It supports standard 
             * JsonPath syntax (e.g., {@code $.store.book[0].title}).
             * </p>
             *
             * @param raw          The raw JSON string to parse. Must not be null or blank.
             * @param jsonPath     The JsonPath expression to evaluate.
             * @param contextName  A descriptive name (usually the moxture name) used to 
             * provide meaningful error messages if extraction fails.
             * @return The extracted value, which may be a {@code String}, {@code Number}, 
             * {@code Boolean}, {@code List}, or {@code Map} depending on the path.
             * @throws IllegalStateException    If the raw input is null or blank, preventing 
             * evaluation of the path.
             * @throws RuntimeException         If the JsonPath is syntactically invalid or 
             * cannot be found within the provided JSON.
             * @see <a href="https://github.com/json-path/JsonPath">JsonPath Documentation</a>
             */
            public static Object extract(String raw, String jsonPath, String contextName) {
                if (raw == null || raw.isBlank()) {
                    throw new IllegalStateException(
                        String.format("Moxture '%s' returned an empty body; cannot read path: %s", 
                        contextName, jsonPath)
                    );
                }
                try {
                    return JsonPath.parse(raw).read(jsonPath);
                } catch (Exception e) {
                    throw new RuntimeException(
                        String.format("Failed to extract '%s' from '%s'. Body: %s", 
                        jsonPath, contextName, raw), e
                    );
                }
            }
        }


        static final class Logging 
        {
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



    // ############################################################################

    /** 
     * POJOs for moxtures + response. 
     */
    static final class Model
    {
        /** 
         * Root of the moxtures file (YAML). 
         */
        static final class MoxtureFile 
        {
            private List<Moxture> moxtures;
            /** Optional top-level variables loaded at engine construction (closest file wins). */
            private Map<String,Object> vars;

            public List<Moxture> moxtures() { return moxtures; }
            public void setMoxtures(List<Moxture> moxtures) { this.moxtures = moxtures; }

            public Map<String,Object> vars() { return vars; }
            public void setVars(Map<String,Object> vars) { this.vars = vars; }
        }

        /** 
         * Definition of a single part in a multipart request. 
         */
        static final class MultipartDef 
        {
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


        /** 
         * One moxture row (HTTP moxture or group moxture when 'moxtures:' present). 
         */
        @Getter @Setter
        static final class Moxture
        {
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
            // group-as-moxture: if present, indicates this row is a group
            private List<String> moxtures;
            private List<MultipartDef> multipart;
            private Map<String, Object> vars;
            // legacy bridge: accept "url" in YAML and map it into "endpoint"
            private String url; // not used directly; setter maps to endpoint if endpoint is missing


            /** Legacy: when YAML provides 'url', treat it as 'endpoint' if endpoint is unset. */
            public void setUrl(String url) {
                this.url = url;
                if ((this.endpoint == null || this.endpoint.isBlank()) && url != null && !url.isBlank()) {
                    this.endpoint = url;
                }
            }
            public String getUrl() { return url; }
        }

        /** 
         * Response wrapper. 
         */
        public static final class ResponseEnvelope 
        {
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





}
