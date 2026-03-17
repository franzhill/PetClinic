package com.fhi.moxter;


import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.assertj.core.api.Assertions;
import org.assertj.core.api.BooleanAssert;
import org.assertj.core.api.ListAssert;
import org.assertj.core.api.ObjectAssert;
import org.assertj.core.api.StringAssert;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fhi.mockWebs.MockWebs;
import com.fhi.moxter.Moxter.MoxRuntime.Templating;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * See readme.md file.
 */
@Slf4j
@SuppressWarnings("java:S125") // all commented out code is intentional
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


    private final IMoxLoader loader; 

    /** 
     * Registry of available executors (HTTP, STOMP, etc.) used to execute moxtures according
     * to the request protocol.
     * For performance reasons we won't be "new-ing" one for every moxture call.
     */
    private final List<MoxRuntime.IProtocolExecutor> executors;

    private final MoxRuntime.VarExtractor extractor;
    private final MoxRuntime.ExpectVerifier verifier;


    // Used if loader = hierarchical package loader
    private Class<?> testClass;

    private final String moxturesBaseDir;

    private final Model.MoxtureFile suite;

    private final Map<String, Model.Moxture> byName;

    // Concurrent allows tests to be run in parallel # TODO
    //#private final ConcurrentMap<String, Object> vars = new ConcurrentHashMap<>();
    private final Map<String,Object> vars = new LinkedHashMap<>();

    private final ObjectMapper yamlMapper;

    // Keep JSON mapper for HTTP
    private final ObjectMapper jsonMapper; // NOSONAR yes it's used!


    public static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    public static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);


    // Auth supplier (fixed or lazy) from builder
    private final Supplier<Authentication> builderAuthSupplier;

    // Caches and Resolvers
    private final Map<MoxResolver.EffectiveKey, Model.Moxture> materializedCache = new LinkedHashMap<>();
    private final MoxVars globalScopeVars = new MoxVars(this, null);
    private final MoxResolver moxResolver = new MoxResolver(this);


    // =====================================================================
    // Public API: 
    // =====================================================================

    /**
     * Creates a fluent {@link MoxBuilder} to configure and instantiate the Moxter engine.
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
     * @return A new {@link MoxBuilder} to finish configuring the engine before calling {@code .build()}.
     */
    public static MoxBuilder forTestClass(Class<?> testClass)
    { return new MoxBuilder(testClass);
    }

    /** 
     * The factory method to spawn a transient caller
     */
    public MoxCaller caller() {
        return new MoxCaller(this);
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
     * @return The {@link MoxVars} facade containing variable management methods.
     * @see MoxVars
     */
    public MoxVars vars() {
        return globalScopeVars;
    }

    /**
     * Access a moxture's locally-scope variables map.
     * 
     * <p>Works the same was as vars(), see that function.
     */
    public MoxVars vars(String moxtureName) {
        return new MoxVars(this, moxtureName);
    }

    // =====================================================================
    //   Builder / construction
    // =====================================================================

    private Moxter(IMoxLoader loader,
                   List<MoxRuntime.IProtocolExecutor> executors,
                   MoxRuntime.VarExtractor extractor,     // Injected
                   MoxRuntime.ExpectVerifier verifier,    // Injected
                   Supplier<Authentication> authSupplier,
                   ObjectMapper yamlMapper,
                   ObjectMapper jsonMapper)
    {
        this.loader              = Objects.requireNonNull(loader, "loader");
        this.executors           = Objects.requireNonNull(executors, "executors");
        this.extractor           = Objects.requireNonNull(extractor, "extractor");
        this.verifier            = Objects.requireNonNull(verifier, "verifier");
        this.builderAuthSupplier = authSupplier;
        this.yamlMapper          = yamlMapper;
        this.jsonMapper          = jsonMapper;

        // 1. Initial Load via Strategy
        IMoxLoader.LoadedSuite loaded = loader.loadInitial();
        this.suite = loaded.suite();
        this.moxturesBaseDir = loaded.baseDir();

        // 2. Load vars via Strategy
        Map<String,Object> initialVars = loader.resolveInitialVars();
        if (initialVars != null && !initialVars.isEmpty()) {
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

        // 3. Index local moxtures
        Map<String, Model.Moxture> index = new LinkedHashMap<>();
        List<Model.Moxture> calls = (suite.moxtures() == null) ? Collections.emptyList() : suite.moxtures();
        for (Model.Moxture f : calls) {
            if (f.getName() == null || f.getName().isBlank()) {
                throw new IllegalStateException("Moxture with missing/blank 'name' in " + moxturesBaseDir + "/" + DEFAULT_MOXTURES_BASENAME);
            }
            MoxResolver.validateMoxture(f);
            if (index.put(f.getName(), f) != null) {
                throw new IllegalStateException("Duplicate moxture name: " + f.getName());
            }
        }
        this.byName = Collections.unmodifiableMap(index);
    }



    // #############################################################################################
    // #############################################################################################
    // #############################################################################################
    //
    // NESTED CLASSES (config/engine/io/runtime/util/model)
    //
    // #############################################################################################
    // #############################################################################################
    // #############################################################################################

    /**
     * A fluent builder for configuring and instantiating the {@link Moxter} engine.
     * 
     * <p>This builder is the entry point for setting up Moxter in your test classes. 
     * It requires the test class (to anchor the classpath search for YAML files) and 
     * a Spring {@link MockMvc} instance to execute the actual HTTP requests.
     * 
     * <p><b>Example Usage:</b>
     * <pre>{@code
     *   Moxter mx = Moxter.forTestClass(MyControllerTest.class)
     *                     .mockMvc(this.mockMvc)
     *                     .build();
     * }</pre>
     */
    public static final class MoxBuilder
    {
        private final Class<?> testClass;
        private MockMvc  mockMvc;
        private MockWebs mockWebs;

        // Authentication provider.
        // Initialized with the default authentication.
        private Supplier<Authentication> authSupplier = () -> SecurityContextHolder.getContext().getAuthentication();

        private MoxBuilder(Class<?> testClass) { 
            this.testClass = testClass; 
        }

        /**
         * Provides the Spring {@link MockMvc} instance that Moxter will use to execute 
         * all HTTP requests defined in the YAML moxtures.
         * 
         * @param mvc The configured MockMvc instance.
         * @return this {@link MoxBuilder} for chaining.
         */
        public MoxBuilder mockMvc(MockMvc mvc) { 
            this.mockMvc = mvc; 
            return this; 
        }

        /**
         * Provides the {@link MockWebs} instance that Moxter will use to execute 
         * all STOMP requests defined in the YAML moxtures.
         * 
         * @param webs The configured MockWebs instance.
         * @return this {@link MoxBuilder} for chaining.
         */
        public MoxBuilder mockWebs(MockWebs webs) { 
            this.mockWebs = webs; 
            return this; 
        }


        /**
         * Provides a fixed Spring Security {@link Authentication} 
         * object to automatically attach to every HTTP request executed by this engine instance.
         *
         * <p>Use this if your entire test suite runs under a single simulated user.
         * 
         * @param auth The Authentication object to inject.
         * @return this {@link MoxBuilder} for chaining.
         */
        public MoxBuilder authentication(Authentication auth) {
            this.authSupplier = () -> auth;
            // The lambda () -> auth will return that exact same object (auth) every single time
            // it is called for the rest of the engine's life
            return this;
        }

        /** 
         * Provides a dynamic supplier for Spring Security {@link Authentication}.
         * 
         * <p>The supplier is evaluated <i>per-request</i>. Use this if your test context 
         * changes the active user dynamically during execution.
         * 
         * @param s The Authentication supplier.
         * @return this {@link MoxBuilder} for chaining.
         */
        public MoxBuilder authenticationSupplier(Supplier<Authentication> s) {
            this.authSupplier = s;
            return this;
        }

        /**
         * Validates the configuration and constructs the final {@link Moxter} engine instance.
         * 
         * <p>During this phase, Moxter will scan the classpath relative to the provided 
         * test class, locate the {@code moxtures.yaml} file, and parse all moxture definitions.
         * 
         * @return A fully initialized, thread-safe Moxter engine.
         * @throws IllegalStateException if the moxtures file cannot be found or contains invalid YAML.
         * @throws NullPointerException if mandatory dependencies (like MockMvc) are missing.
         */
        public Moxter build() 
        {
            // Wire up the legacy JUnit hierarchical config
            HierarchicalMoxLoader.MoxLoadingConfig cfg = new HierarchicalMoxLoader.MoxLoadingConfig(
                    DEFAULT_MOXTURES_ROOT_PATH,
                    DEFAULT_USE_PER_TESTCLASS_DIRECTORY,
                    DEFAULT_MOXTURES_BASENAME
            );
            
            // Use the static constants here!
            IMoxLoader loader = new HierarchicalMoxLoader(testClass, cfg, YAML_MAPPER);

            // Create SHARED Runtime helpers (Temporary local instances for the builder to use)
            MoxRuntime.Templating   templating   = new MoxRuntime.SimpleTemplating();
            MoxRuntime.BodyResolver bodyResolver = new MoxRuntime.BodyResolver(YAML_MAPPER);
            
            // Create the ORCHESTRATION components
            MoxRuntime.VarExtractor extractor = new MoxRuntime.VarExtractor();
            
            // The verifier gets the helpers it needs right here:
            MoxRuntime.ExpectVerifier verifier = new MoxRuntime.ExpectVerifier(
                bodyResolver, JSON_MAPPER, templating, mockWebs);
            
            // Register Available Protocol Executors
            List<MoxRuntime.IProtocolExecutor> executors = new ArrayList<>();
            
            // If MocMvc has been provided, we'll most probably at some point be executing http
            //  moxtures => we'll need the HttpExecutor => instantiate it and place it in the 
            // list of available executors.
            if (mockMvc != null) {
                executors.add(new MoxRuntime.HttpExecutor(
                    mockMvc, 
                    defaultJsonMapper(), 
                    templating, 
                    bodyResolver, 
                    authSupplier  // Shared engine-level identity supplier
                ));
            }

            // If mockWebs has been provided, we'll most probably at some point be executing STOMP
            //  moxtures => we'll need the StompExecutor => instantiate it and place it in the 
            // list of available executors.
            if (mockWebs != null) {
                executors.add(
                    new MoxRuntime.StompExecutor(
                        mockWebs, 
                        defaultJsonMapper(), 
                        templating,
                        bodyResolver, 
                        authSupplier // Shared engine-level identity supplier
                ));
            }
            
            // 4. Return the fully wired engine
            return new Moxter(loader, executors, extractor, verifier, authSupplier, 
                              defaultYamlMapper(), defaultJsonMapper());
        }


        // =====================================================================
        //   Private helpers
        // =====================================================================

        private static ObjectMapper defaultYamlMapper() {
            return YAML_MAPPER;
        }

        private static ObjectMapper defaultJsonMapper() {
            return JSON_MAPPER;
        }
    }

    // #############################################################################################


    /**
     * Strategy interface for discovering and loading moxture definitions.
     * 
     * <p>By abstracting the discovery logic, Moxter can support different loading 
     * paradigms—such as JUnit-style hierarchical classpath scanning or standalone 
     * explicit file imports -without modifying the core execution engine.
     */
    public interface IMoxLoader {

        /**
         * Performs the initial load of the primary moxture file (the entry point).
         * 
         * <p>For a hierarchical strategy, this is the file closest to the test class. 
         * For an explicit strategy, this is the file specifically requested by the user.
         * 
         * @return A {@link LoadedSuite} containing the parsed entry-point file and its 
         *         anchor directory.
         * @throws RuntimeException if the entry-point file cannot be found or parsed.
         */
        LoadedSuite loadInitial();

        /**
         * Resolves the initial variables map used to seed the engine's global context.
         * 
         * <p>Implementations determine how these variables are gathered. A hierarchical 
         * loader might walk up the package tree to merge variables, while an explicit 
         * loader might only read the current file and its explicit includes.
         * 
         * @return A map of variables discovered through the loading strategy, or an empty 
         *         map if none exist.
         */
        Map<String, Object> resolveInitialVars();

        /**
         * Resolves a moxture definition by name on-demand.
         * * <p>This method implements the "Anti-Zombie" logic: it only searches paths 
         * relevant to the current strategy (e.g., package ancestors or explicit includes). 
         * If a faulty file exists elsewhere in the project, it is ignored unless 
         * strictly required by the resolution path.</p>
         * * @param name           The name of the moxture to find (e.g., for a 'basedOn' or 'call' resolution).
         * @param currentBaseDir The directory of the file currently being processed, used as a starting point for relative lookups.
         * @return A {@link RawMoxture} containing the unmaterialized definition, or {@code null} if not found.
         */
        RawMoxture findByName(String name, String currentBaseDir);
        // =========================================================================
        // Data Carriers
        // =========================================================================
        /**
         * Container for a fully parsed Moxture entry-point file and its location.
         * * @param suite   The parsed object graph of the YAML file.
         * @param baseDir The directory where this file resides (used to resolve relative paths like 'classpath:req.json').
         */
        record LoadedSuite(Model.MoxtureFile suite, String baseDir) {}

        /**
         * Container for a raw, unmaterialized moxture discovered during on-demand resolution.
         * * @param moxt        The raw moxture definition exactly as it appears in the YAML file.
         * @param baseDir     The directory where this specific moxture's file resides.
         * @param displayPath A human-readable path (e.g., "classpath:/my/pkg/moxtures.yaml") used for DX-friendly error reporting.
         */
        record RawMoxture(Model.Moxture moxt, String baseDir, String displayPath) {}
    }


    // #############################################################################################

    /**
     * A MoxtureLoader that follows the Java package hierarchy to discover moxtures.
     * 
     * <p>This strategy mirrors the test class structure. It starts searching from 
     * the test's specific package and walks up the tree to the root. This allows 
     * for localized overrides and global defaults within a shared classpath.</p>
     * 
     * Resolve parent by searching upwards (closest → parents → root)
     */
    @Slf4j
    public static class HierarchicalMoxLoader implements IMoxLoader {

        private final Class<?> testClass;
        private final MoxLoadingConfig cfg;
        private final ObjectMapper yamlMapper;
        private final MoxClasspathRepository repo;

        public HierarchicalMoxLoader(Class<?> testClass, MoxLoadingConfig cfg, ObjectMapper yamlMapper) {
            this.testClass = Objects.requireNonNull(testClass, "testClass");
            this.cfg = Objects.requireNonNull(cfg, "cfg");
            this.yamlMapper = Objects.requireNonNull(yamlMapper, "yamlMapper");
            this.repo = new MoxClasspathRepository(yamlMapper);
        }

        @Override
        public LoadedSuite loadInitial() {
            MoxClasspathRepository.RepoLoadedSuite res = repo.loadFor(testClass, cfg);
            return new LoadedSuite(res.suite(), res.baseDir());
        }

        @Override
        public RawMoxture findByName(String name, String currentBaseDir) {
            MoxClasspathRepository.RepoRawMoxture res = repo.findFirstByNameFromBaseDir(testClass, cfg, currentBaseDir, name, yamlMapper);
            return res == null ? null : new RawMoxture(res.call(), res.baseDir(), res.displayPath());
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> resolveInitialVars() 
        {
            // 1. Convert Class to Resource Path (e.g., "com/fhi/app/MyTest")
            String pkg = (testClass.getPackageName() == null ? "" : testClass.getPackageName().replace('.', '/'));
            String startPath = cfg.rootPath + (pkg.isEmpty() ? "" : "/" + pkg);
            
            // 2. Add the class sub-directory if configured
            if (cfg.perTestClassDirectory) {
                startPath += "/" + testClass.getSimpleName();
            }

            // 3. Get the list of potential files from the test class up to the root
            // Logic: Get every moxtures.yaml from my package up to the root to merge variables
            // Call the generic walkUp with the string path
            List<String> candidates = Utils.Classpath.walkUp(startPath, cfg.rootPath, cfg.fileName);

            Map<String, Object> last = null;

            for (String cp : candidates) {
                // 2. REPLACED: Tiered classloader logic is now encapsulated here
                URL url = Utils.IO.findResource(cp, testClass);
                if (url == null) continue;

                try (InputStream in = url.openStream()) {
                    // 3. REPLACED: Manual mapper.readValue is now Utils.Yaml.parseFile
                    // This provides the "Big Red Box" error if a parent YAML is broken
                    Model.MoxtureFile suite = Utils.Yaml.parseFile(yamlMapper, in, "classpath:/" + cp);
                    
                    Map<String, Object> varsFromFile = suite.vars();
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

        // =========================================================================
        // Configuration
        // =========================================================================

        /** 
         * Immutable configuration used for locating moxtures on classpath. 
         */
        public static final class MoxLoadingConfig {
            final String rootPath;                // e.g., "integrationtests2/moxtures"
            final boolean perTestClassDirectory;  // true => add "/{TestClassName}"
            final String fileName;                // "moxtures.yaml" (includes extension)

            public MoxLoadingConfig(String rootPath, boolean perTestClassDirectory, String fileName) {
                this.rootPath = trim(rootPath);
                this.perTestClassDirectory = perTestClassDirectory;
                this.fileName = trim(fileName);
            }
            private static String trim(String s) {
                if (s == null) return "";
                String out = s.trim();
                while (out.startsWith("/")) out = out.substring(1);
                while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
                return out;
            }
        }

        // =========================================================================
        // Internal Repository Logic
        // =========================================================================

        /**
         * Classpath repository:
         * - Build exact closest path: rootPath + "/" + {package as folders} + ["/{TestClassName}"] + "/" + fileName
         * - Try TCCL, fall back to test class CL, then this class CL.
         * - If not found, throw with a clear message.
         * - Provides hierarchical name lookup helpers.
         */
        static final class MoxClasspathRepository {
            private final ObjectMapper mapper;

            MoxClasspathRepository(ObjectMapper mapper) { this.mapper = mapper; }

            record RepoLoadedSuite(Model.MoxtureFile suite, String baseDir) {}
            record RepoRawMoxture(Model.Moxture call, String baseDir, String displayPath) {}

            public RepoLoadedSuite loadFor(Class<?> testClass, MoxLoadingConfig cfg) {
                final String classpath = buildClosestClasspath(testClass, cfg);
                final String displayPath = "classpath:/" + classpath;

                URL url = Utils.IO.findResource(classpath, testClass);

                // CHANGE: Instead of throwing IllegalStateException, handle the missing file gracefully
                if (url == null) {
                    log.warn("[Moxter] No specific moxtures.yaml found for {}. Searched at: {}. " +
                            "Moxter will rely on parent/global moxtures if available.", 
                            testClass.getName(), displayPath);
                    
                    // Return an empty suite so the engine doesn't crash during boot
                    return new RepoLoadedSuite(new Model.MoxtureFile(), Utils.IO.parentDirOf(classpath));
                }

                log.debug("[Moxter] Loading {} -> {}", displayPath, url);

                try (InputStream in = url.openStream()) {
                    Model.MoxtureFile suite = Utils.Yaml.parseFile(mapper, in, displayPath);
                    String baseDir = Utils.IO.parentDirOf(classpath);
                    return new RepoLoadedSuite(suite, baseDir);
                } catch (IOException e) {
                    throw new RuntimeException("Failed loading moxtures: " + displayPath, e);
                }
            }

           /* ===== Hierarchical lookup (helpers) ===== */

            /** Returns candidate ancestor classpaths from closest → root (inclusive). */
            List<String> candidateAncestorPaths(Class<?> testClass, MoxLoadingConfig cfg) {
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


            /** 
             * Finds the first (closest) occurrence of a moxture by name, starting from an arbitrary baseDir. 
             */
            RepoRawMoxture findFirstByNameFromBaseDir(Class<?> testClass, MoxLoadingConfig cfg,
                                                    String startBaseDir, String name, ObjectMapper yamlMapper) {
                
                // 1. Generate the candidate ancestor paths 
                List<String> candidates = Utils.Classpath.walkUp(startBaseDir, cfg.rootPath, cfg.fileName);

                for (String cp : candidates) {
                    // We pass testClass to provide the secondary ClassLoader context
                    URL url = Utils.IO.findResource(cp, testClass); 
                    
                    if (url == null) continue;

                    try (InputStream in = url.openStream()) {
                        Model.MoxtureFile raw = Utils.Yaml.parseFile(yamlMapper, in, "classpath:/" + cp);

                        if (raw.moxtures() == null || raw.moxtures().isEmpty()) continue;

                        for (Model.Moxture f : raw.moxtures()) {
                            if (name.equals(f.getName())) {
                                String baseDir = Utils.IO.parentDirOf(cp);
                                return new RepoRawMoxture(f, baseDir, "classpath:/" + cp);
                            }
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("Failed reading " + cp, e);
                    }
                }
                return null;
            }


            private static String buildClosestClasspath(Class<?> testClass, MoxLoadingConfig cfg) {
                String pkg = (testClass.getPackageName() == null ? "" : testClass.getPackageName().replace('.', '/'));
                String classDir = cfg.perTestClassDirectory ? ("/" + testClass.getSimpleName()) : "";
                String base = (pkg.isEmpty() ? cfg.rootPath : (cfg.rootPath + "/" + pkg)) + classDir + "/";
                String full = base + cfg.fileName;
                log.debug("[Moxter] Expecting moxtures at: classpath:/{} for {}", full, testClass.getName());
                return full;
            }
        }
    }



    // #############################################################################################




    /**
     * Internal engine component responsible for the discovery, inheritance, and flattening 
     * of moxture definitions.
     * 
     * <p>The {@code MoxResolver} handles the "Materialization" phase of the moxture lifecycle. 
     * Its primary responsibility is to transform a declarative YAML definition—which may 
     * use hierarchical inheritance via {@code basedOn}—into a fully resolved, executable 
     * {@code EffectiveMoxture}.
     * 
     * <p><b>Key Responsibilities:</b>

     *   - <b>Lookup:</b> Performs a "closest-first" search for moxtures by name, 
     *        starting from the test class directory and walking up through parent packages 
     *        to the root moxtures directory.
     *   - <b>Deep Materialization:</b> Recursively resolves inheritance chains (via {@code basedOn} 
     *        or {@code extends}). It merges parents and children using specific precedence rules:
     *     <ul>
     *       - Scalars (method, endpoint) are overridden by the child.
     *       - Maps (headers, query, vars) are shallow-merged (child wins).
     *       - Lists (save, moxtures) are replaced entirely by the child.
     *     </ul>
     *    
     *    - <b>Cycle Detection:</b> Prevents infinite recursion in inheritance chains by 
     *        maintaining a visiting stack and throwing an {@link IllegalStateException} 
     *        if a cycle is detected.
     *    - <b>Body Stacking:</b> Instead of merging JSON bodies during resolution, 
     *        it builds a {@code bodyStack} to preserve the hierarchy. This allows the 
     *        {@code BodyResolver} to handle variable interpolation and deep-merging 
     *        correctly at runtime.

     * 
     * <p><b>Caching:</b> Resolved moxtures are cached in the {@code materializedCache} 
     * to ensure performance and consistency throughout the test suite execution.
     */
    public static final class MoxResolver
    {
        // Reference to the root encompassing engine
        private final Moxter moxter;

        protected MoxResolver(Moxter moxter) {
            this.moxter = moxter;
        }

        /**
         * An internal container representing a fully prepped, executable moxture.
         * (aka resolved/materialized/effective moxture)
         *
         * <p>This class pairs a completely materialized/effective moxture definition (where all 
         * {@code basedOn} inheritance and hierarchical merging have been flattened) 
         * with the physical classpath directory where it was discovered.
         *
         * <p>Keeping track of the {@code baseDir} is critical during the execution phase, 
         * as it acts as the anchor point for resolving relative file imports (e.g., when 
         * a body or multipart file is defined as {@code "classpath:request.json"}).
         */
        private static final class EffectiveMoxture 
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

            EffectiveMoxture(Model.Moxture moxt, String baseDir) 
            { this.moxt = moxt; this.baseDir = baseDir; 
            }
        }

        /** 
         * Key for memorizing materialized/effective moxtures by scope (baseDir) and name. 
         */
        private static final class EffectiveKey 
        {
            final String baseDir; // classpath dir where the moxture is defined
            final String name;
            
            EffectiveKey(String baseDir, String name) { this.baseDir = baseDir; this.name = name; }
            
            public boolean equals(Object o)
            { if(this==o)return true;
                if(!(o instanceof EffectiveKey)) return false;
                EffectiveKey k=(EffectiveKey)o;
                return Objects.equals(baseDir,k.baseDir)&&Objects.equals(name,k.name); 
            }
            
            public int hashCode(){ return Objects.hash(baseDir,name); }

            public String toString(){ return baseDir+":"+name; }
        }


        /** 
         * Resolve a name to a concrete, fully materialized moxture (closest → parents), with its baseDir. 
         */
        private EffectiveMoxture resolveByName(String name)
        {
            // 1) Try closest file first
            Model.Moxture local = moxter.byName.get(name);
            if (local != null) {
                Model.Moxture mat = materializeDeep(local, moxter.moxturesBaseDir, new ArrayDeque<>(), new HashSet<>());
                return new EffectiveMoxture(mat, moxter.moxturesBaseDir);
            }

            // 2) Delegate discovery to the Strategy Loader
            IMoxLoader.RawMoxture found = moxter.loader.findByName(name, moxter.moxturesBaseDir);
            if (found == null) {
                throw new IllegalArgumentException("Moxture/Group not found by name: " + name);
            }

            // Ensure deep materialization from the found scope
            Model.Moxture mat = materializeDeep(found.moxt(), found.baseDir(), new ArrayDeque<>(), new HashSet<>());
            return new EffectiveMoxture(mat, found.baseDir());
        }



        /**
         * Fully materialize a moxture: follow {@code basedOn} across files to any depth.
         * Merges at each step with child precedence, using existing merge rules:
         *  - Scalars: child overrides
         *  - headers/query (maps): shallow merge, child wins
         *  - save / moxtures (lists-of-names): REPLACE
         *  - body: deep-merge objects; arrays/scalars replace
         *
         * Cycle-safe with a visiting set + stack (human-friendly chain on error).
         */
        private Model.Moxture materializeDeep(Model.Moxture node,
                                                String nodeBaseDir,
                                                Deque<EffectiveKey> stack,
                                                Set<EffectiveKey> visiting)
        {
            if (node == null) return null;

            final String parentName = Utils.Misc.firstNonBlank(node.getBasedOn(), node.getBasedOn());
            final String nodeName = (node.getName() == null || node.getName().isBlank()) ? "<unnamed>" : node.getName();
            final EffectiveKey key = new EffectiveKey(nodeBaseDir, nodeName);

            // Cache
            Model.Moxture cached = moxter.materializedCache.get(key);
            if (cached != null) return cached;

            // No inheritance → normalize + cache
            if (parentName == null || parentName.isBlank()) {
                Model.Moxture normalized = cloneWithoutBasedOn(node);
                moxter.materializedCache.put(key, normalized);
                return normalized;
            }

            // Cycle guard
            if (!visiting.add(key)) {
                StringBuilder sb = new StringBuilder("Cycle in basedOn: ");
                for (EffectiveKey k : stack) sb.append(k).append(" -> ");
                sb.append(key);
                throw new IllegalStateException(sb.toString());
            }
            stack.addLast(key);

            
            // Resolve parent by searching via the Strategy Loader
            IMoxLoader.RawMoxture parentResolved = moxter.loader.findByName(parentName, nodeBaseDir);

            if (parentResolved == null) {
                throw new IllegalArgumentException(
                    "basedOn refers to unknown moxture '" + parentName + "' (searched from " + nodeBaseDir + ")"
                );
            }

            // Recurse
            Model.Moxture materializedParent =
                    materializeDeep(parentResolved.moxt(), parentResolved.baseDir(), stack, visiting);
            // Merge parent → child (child overrides)
            Model.Moxture merged = new Model.Moxture();
            merged.setName(node.getName());

            // Protocol: check: redefeining the protocol at child level is not allowed (makes no sense)
            String parentProto = materializedParent.getProtocol();
            String childProto = node.getProtocol();
            if (childProto != null && !childProto.isBlank() && parentProto != null && !parentProto.isBlank()) {
                if (!childProto.equalsIgnoreCase(parentProto)) {
                    throw new IllegalStateException(String.format(
                        "Moxture '%s' attempts to override parent protocol '%s' with '%s'. Protocol overriding is not allowed.",
                        nodeName, parentProto, childProto
                    ));
                }
            }
            merged.setProtocol(Utils.Misc.firstNonBlank(childProto, parentProto));

            merged.setMethod(Utils.Misc.firstNonBlank(node.getMethod(), materializedParent.getMethod()));
            merged.setEndpoint(Utils.Misc.firstNonBlank(node.getEndpoint(), materializedParent.getEndpoint()));
            merged.setExpect(node.getExpect() != null ? node.getExpect() : materializedParent.getExpect());
            merged.setOptions(node.getOptions() != null ? node.getOptions() : materializedParent.getOptions());
            merged.setHeaders(Utils.Misc.mergeMap(materializedParent.getHeaders(), node.getHeaders()));
            merged.setVars(Utils.Misc.mergeMap(materializedParent.getVars(), node.getVars()));
            merged.setQuery(Utils.Misc.mergeMap(materializedParent.getQuery(), node.getQuery()));
            // For "save": replace instead of merge (no inheritance unless explicitly set on the child)
            merged.setSave(node.getSave() != null ? node.getSave() : materializedParent.getSave());
            // For "moxtures" (group list): replace instead of merge
            merged.setMoxtures(node.getMoxtures() != null ? node.getMoxtures() : materializedParent.getMoxtures());
            merged.setMultipart(node.getMultipart() != null ? node.getMultipart() : materializedParent.getMultipart());

            // Merge options block:
            Model.RootOptionsDef parentOpts = materializedParent.getOptions();
            Model.RootOptionsDef childOpts = node.getOptions();
            
            if (parentOpts != null || childOpts != null) {
                Model.RootOptionsDef mergedOpts = new Model.RootOptionsDef();
                if (parentOpts == null) parentOpts = new Model.RootOptionsDef();
                if (childOpts == null) childOpts = new Model.RootOptionsDef();

                mergedOpts.setVerbose(childOpts.getVerbose() != null ? childOpts.getVerbose() : parentOpts.getVerbose());
                mergedOpts.setAllowFailure(childOpts.getAllowFailure() != null ? childOpts.getAllowFailure() : parentOpts.getAllowFailure());
                merged.setOptions(mergedOpts);
            }

            // Body block: Build the stack instead of merging nodes
            List<JsonNode> combinedStack = new ArrayList<>(materializedParent.getBodyStack());
            if (node.getBody() != null) {
                combinedStack.add(node.getBody());
            }
            merged.setBodyStack(combinedStack);
            
            // Keep the 'body' field pointing to the latest definition for backward compatibility
            merged.setBody(node.getBody() != null ? node.getBody() : materializedParent.getBody());

            // Clear inheritance markers on the final node
            merged.setBasedOn(null);

            validateMoxture(merged);

            moxter.materializedCache.put(key, merged);
            stack.removeLast();
            visiting.remove(key);
            return merged;
        }

        /** 
         * Ensure a moxture is either a group OR a single call, not both. 
         */
        private static void validateMoxture(Model.Moxture f) {
            boolean isGroup = f.getMoxtures() != null;
            boolean isHttp  = f.getEndpoint() != null; // this field is mandatory 
/* OLD
            (f.getEndpoint() != null && !f.getEndpoint().isBlank()) ||
                (f.getMethod()   != null && !f.getMethod().isBlank())   ||
                f.getBody() != null ||
                f.getExpectedStatus() != null ||
                (f.getHeaders() != null && !f.getHeaders().isEmpty()) ||
                (f.getQuery()   != null && !f.getQuery().isEmpty())   ||
                (f.getSave()    != null && !f.getSave().isEmpty());
 */                
            if (isGroup && isHttp) {
                throw new IllegalStateException("Moxture '" + f.getName() + "' cannot define both 'moxtures' and HTTP fields");
            }
        }


        /**
         * Creates a safe, shallow clone of a moxture definition, completely detaching 
         * it from its inheritance chain while preserving 
         * the deeply-merged bodies (=> 'bodyStack')
         * 
         * "Detaching" means we resolve the inheritance once, copy the data, and destroy
         * the pointer to the parent so the execution engine operates blazingly fast on 
         * a flat object. 
         * 
         * "Preserving the stack" means we deliberately delay merging the JSON bodies
         * until the exact millisecond the HTTP request fires.
         * 
         * When and why this is used:
         * 1. The Caching Phase (Static):  Inside materializeDeep, when 
         *     a moxture has no parent (or we reach the top of an inheritance chain), 
         *     we clone it before placing it in the materializedCache. This 
         *     protects the raw, parsed YAML definitions from accidental mutation.
         * 2. The Execution Phase (Runtime): Inside MoxtureCaller.blendRuntimeOptions, 
         *    before a moxture is actually executed, we fetch the static version from 
         *    the cache and clone it. This provides a disposable "Effective Spec" where 
         *    runtime Java API overrides (like .allowFailure(true) or .verbose(true)) 
         *    can be safely applied without permanently poisoning the engine's shared cache 
         *    for subsequent tests.
         * 
         * @param src The source moxture to clone.
         * @return A disposable, standalone clone safe for runtime mutation.
         */
        private static Model.Moxture cloneWithoutBasedOn(Model.Moxture src) {
            Model.Moxture c = new Model.Moxture();
            c.setName(src.getName());
            c.setProtocol(src.getProtocol());
            c.setMethod(src.getMethod());
            c.setEndpoint(src.getEndpoint());
            c.setHeaders(src.getHeaders()==null?null:new LinkedHashMap<>(src.getHeaders()));
            c.setVars(src.getVars() == null ? null : new LinkedHashMap<>(src.getVars()));
            c.setQuery(src.getQuery()==null?null:new LinkedHashMap<>(src.getQuery()));
            c.setSave(src.getSave()==null?null:new LinkedHashMap<>(src.getSave()));
            c.setExpect(src.getExpect());
            if (src.getOptions() != null) {
                Model.RootOptionsDef opt = new Model.RootOptionsDef();
                opt.setAllowFailure(src.getOptions().getAllowFailure());
                opt.setVerbose(src.getOptions().getVerbose());
                c.setOptions(opt);
            }
            c.setMoxtures(src.getMoxtures()==null?null:new ArrayList<>(src.getMoxtures()));
            c.setMultipart(src.getMultipart() == null ? null : new ArrayList<>(src.getMultipart()));
            c.setBasedOn(null);

            //# OLD c.setBody(src.getBody()); // JSON nodes are fine to share for our usage
            // THE FIX: Prevent Shared Mutable State
            // Use Jackson's deepCopy() so runtime body modifications don't poison the cache:
            c.setBody(src.getBody() == null ? null : src.getBody().deepCopy());

            // Preserve the body stack if it was already built!
            if (src.getBodyStack() != null && !src.getBodyStack().isEmpty()) {
                c.setBodyStack(new ArrayList<>(src.getBodyStack()));
            } else if (src.getBody() != null) {
                c.getBodyStack().add(src.getBody());
            }

            return c;
        }

        /**
         * Recursively merges two JSON nodes, with the child node taking precedence.
         * 
         * <p>Merges nested ObjectNodes. For arrays and value nodes (strings, numbers), 
         * the child simply replaces the parent.
         */
        private static JsonNode deepMergeBody(ObjectMapper mapper, JsonNode parent, JsonNode child) {
            // 1. Coerce to objects if they are JSON strings (though resolveSingleNode usually handles this)
            parent = Utils.Json.coerceJsonTextToNode(mapper, parent);
            child  = Utils.Json.coerceJsonTextToNode(mapper, child);

            if (child == null) return parent;
            if (parent == null) return child;

            // 2. Recursive Object Merge
            if (child.isObject() && parent.isObject()) {
                ObjectNode merged = (ObjectNode) parent.deepCopy();
                Iterator<Map.Entry<String, JsonNode>> fields = child.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String key = entry.getKey();
                    JsonNode childVal = entry.getValue();
                    JsonNode parentVal = merged.get(key);

                    if (childVal.isObject() && parentVal != null && parentVal.isObject()) {
                        merged.set(key, deepMergeBody(mapper, parentVal, childVal));
                    } else {
                        merged.set(key, childVal.deepCopy());
                    }
                }
                return merged;
            }

            // 3. Recursive Array Merge (Merged by index)
            if (child.isArray() && parent.isArray()) {
                ArrayNode parentArr = (ArrayNode) parent;
                ArrayNode childArr = (ArrayNode) child;
                ArrayNode mergedArr = parentArr.deepCopy();

                for (int i = 0; i < childArr.size(); i++) {
                    JsonNode childItem = childArr.get(i);
                    if (i < mergedArr.size()) {
                        // If both items at this index exist, merge them recursively
                        mergedArr.set(i, deepMergeBody(mapper, mergedArr.get(i), childItem));
                    } else {
                        // If child has more items, append them
                        mergedArr.add(childItem.deepCopy());
                    }
                }
                return mergedArr;
            }

            // 4. Fallback: Child replaces Parent (Scalars or Mismatched Types)
            return child.deepCopy();
        }

    }


    // #############################################################################################




    /**
     * A fluent builder offered to end-used to configure and execute Moxture calls.
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
    public static class MoxCaller 
    {
        // Reference to the root encompassing engine
        private final Moxter moxter;

        private Map<String, Object> varOverrides = new LinkedHashMap<>();
        private Object expectedStatusOverride = null;

        private Boolean allowFailure = null; // Not boolean
        private Boolean verbose = null; // replacing printReturn
        private boolean jsonPathLax = false;

        /**
         * The specific Spring Security Authentication bound to this specific moxture caller.
         * 
         * <p>When set (via {@link #withAuth(Authentication)}), 
         * this identity overrides both the engine (Moxter) 's default authentication supplier and 
         * the global {@code SecurityContextHolder}.
         * 
         * <p>This security context persists for the lifetime of this specific {@code MoxtureCaller} 
         * instance. Because it is strictly bound to the caller rather than the root engine (Moxter),
         * it allows you to safely execute one or more moxtures as a specific user without polluting
         * the global test state.
         */
        private Authentication callAuth = null;


        /**
         * Nested builder for overriding moxture expectations fluently.
         */
        public class ExpectBuilder {
            
            /**
             * Overrides the expected HTTP status code(s) defined in the YAML moxture.
             * 
             * @param status Can be an Integer (201), a String ("4xx"), or a List ([200, 204]).
             * @return The parent {@link MoxCaller} to continue chaining.
             */
            public MoxCaller status(Object status) {
                MoxCaller.this.expectedStatusOverride = status;
                return MoxCaller.this;
            }
            
            // Future expansion: public MoxtureCaller body(...) { ... }
        }



        /**
         * For end-users: use Moxter.caller() instead
         * 
         * @param moxter      The encompassing Engine.
         */
        protected MoxCaller(Moxter moxter) {
            this.moxter = moxter;
        }

        /**
         * Toggles 'allowFailure' mode for this specific call.
         *
         * When enabled, exceptions and assertion errors are caught and logged
         * as warnings yet will not fail the moxture.
         * 
         * Lets us have "best effort" moxtures the good completion of which are not
         * strictly mandatory.
         */
        public MoxCaller allowFailure(boolean allowFailure) {
            this.allowFailure = allowFailure;
            return this;
        }

        /**
         * Increases the console feedback (disregarding logger threshold)
         */
        public MoxCaller verbose(boolean verbose) {
            this.verbose = verbose;
            return this;
        }

        /**
         * Injects a specific Spring Security {@link org.springframework.security.core.Authentication} 
         * to be used strictly for this individual moxture execution.
         * 
         * <p>This method enables seamless context-switching within a single test scenario. By providing a 
         * call-scoped authentication, you can effortlessly simulate different users (e.g., User A locking a 
         * resource, and User B attempting to bypass it) interacting sequentially without permanently mutating 
         * the global engine state or the thread's overarching {@code SecurityContext}.
         * 
         * <p><b>Security Resolution Precedence:</b>
         * Authentication provided via this method takes the highest priority for the HTTP request, overriding:
         * - The engine-level {@code Authentication} (configured via {@code MoxBuilder}).
         * - The global {@code SecurityContextHolder}.
         * 
         * <p><b>Example Usage:</b>
         * <pre>{@code
         *    mx.caller()
         *      .withAuthentication(principalUserB) // Execute this specific call as User B
         *      .with("p.objectId", 4317)
         *      .call("com.update_field_locked");
         * }</pre>
         * 
         * @param auth The Spring Security Authentication object representing the identity for this specific call.
         * @return this {@link MoxCaller} instance for fluent method chaining.
         */
        public MoxCaller withAuth(Authentication auth) {
            this.callAuth = auth;
            return this;
        }


        /**
         * Toggles 'Lax' mode for JsonPath extractions.
         * 
         * If true, failed JsonPath extractions will return null rather than throwing an exception.
         * 
         * @param val true to enable lax JsonPath evaluation.
         * @return this {@link MoxCaller} for chaining.
         */
        public MoxCaller withJsonPathLax(boolean val) {
            this.jsonPathLax = val;
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
         * @return this {@link MoxCaller} for chaining.
         */
        public MoxCaller withVars(Map<String, Object> overrides) {
            if (overrides != null) this.varOverrides.putAll(overrides);
            return this;
        }

        /**
         * Injects a single variable into the moxture call context.
         * 
         * @param key   The variable name (used as ${key} in YAML).
         * @param value The value to inject.
         * @return this {@link MoxCaller} for chaining.
         */
        public MoxCaller withVar(String key, Object value) {
            this.varOverrides.put(key, value);
            return this;
        }

        /**
         * Overrides the expected HTTP status code(s) defined in the YAML moxture.
         * * @param status Can be an Integer (201), a String ("4xx"), or a List ([200, 204]).
         */
        public MoxCaller expect(Object status) {
            this.expectedStatusOverride = status;
            return this;
        }

        /**
         * Enters the expectation override builder.
         * Allows you to surgically override expectations defined in the YAML file for this specific call.
         */
        public ExpectBuilder expect() {
            return new ExpectBuilder();
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

         *   - <b>Reads:</b> when templating, header/query resolution, or body
         *       substitution looks up a variable, the engine will check the call-scoped
         *       overrides first. If the key is not present there, it falls back to the
         *       engine’s global variables.
         *   - <b>Writes:</b> when a moxture specifies a "save:" block, the
         *       extracted values are always written into the engine’s global variables,
         *       never into the call-scoped overrides. This ensures saved IDs or tokens
         *       are available to subsequent moxtures and test code.

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
         * @param varOverrides per-call variable overrides (not mutated; keys shadow globals)
         * @return {@code null} for group moxtures; for single moxtures, the response envelope.
         */
        public MoxtureResult call(String moxtureName) 
        {
            MoxRuntime.ResponseEnvelope env = callInternal(moxtureName, varOverrides);

            return new MoxtureResult(env, moxter, moxtureName);
        }


        /**
         * TODO 
         * @param moxtureName
         * @param overrides
         * @return
         */
        private MoxRuntime.ResponseEnvelope callInternal(String moxtureName, Map<String, Object> overrides) 
        {
            Objects.requireNonNull(moxtureName, "name");
            MoxResolver.EffectiveMoxture r = this.moxter.moxResolver.resolveByName(moxtureName);

            // ---> THE BLENDER <---
            // Merge the YAML spec with the runtime Java overrides
            Model.Moxture finalSpec = blendInRuntimeOptions(r.moxt);

            // 1. Build this moxture's local overrides.
            // The moxture's own YAML vars yield to the passed-in overrides.
            // (If this is a child in a group, callScopedOverrides contains the group's vars)
            Map<String, Object> localOverrides = new LinkedHashMap<>();

            // Build the current running context (globals + call overrides) to resolve interpolations
            Map<String, Object> runningContext = new LinkedHashMap<>(this.moxter.vars().view());
            if (overrides != null) {
                runningContext.putAll(overrides);
            }
            
            // Local vars provided by the moxture (or the group of moxtures):
            if (finalSpec.getVars() != null) {
                finalSpec.getVars().forEach((k, v) -> {
                    if (v instanceof String s) {
                        // Resolve aliases like "{{ownerId}}" to their actual values
                        localOverrides.put(k, Templating.interpolate(s, runningContext));
                    } else {
                        localOverrides.put(k, v);
                    }
                });
            }

            // Add vars provided by the call or by the previous step (in the case of a group moxture)
            // These take precedence over local vars.
            if (overrides != null) {
                localOverrides.putAll(overrides);
            }

            // 2. Call
            MoxResolver.validateMoxture(finalSpec);
            boolean isAllowFailure = finalSpec.getOptions() != null && Boolean.TRUE.equals(finalSpec.getOptions().getAllowFailure());

            // 2.1. If group Moxture
            if (isGroupMoxture(finalSpec)) 
            {   final String label = "group '" + moxtureName + "'" + (isAllowFailure ? " (allowFailure)" : "");
                
                // We pass THIS group's successfully merged localScope down to its children.
                // To the children, these act as their "callScopedOverrides".
                for (String childMoxtureName : finalSpec.getMoxtures()) 
                {   try 
                    {   // Recurse
                        callInternal(childMoxtureName, localOverrides);
                    } 
                    catch (Throwable t) 
                    {   if (isAllowFailure) {
                            log.warn("[Moxter] (allowFailure) {} → child '{}' failed — skipping. Cause: {}", label, childMoxtureName, t.toString());
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
                Map<String, Object> mergedVars = new MoxVars.CallScopedVars(localOverrides, this.moxter.vars, moxter.vars()::put);
                try 
                {
                    // Phase 1: Network I/O
                    MoxRuntime.IProtocolExecutor executor = this.moxter.executors.stream()
                            .filter(e -> e.supports(finalSpec))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                "No executor registered for protocol: " + (finalSpec.getProtocol() == null ? "http" : finalSpec.getProtocol())
                            ));
                    MoxRuntime.ResponseEnvelope env = executor.execute(finalSpec, r.baseDir, mergedVars, this.callAuth);

                    if (env != null) {
                        Configuration jsonPathConfig = jsonPathLax ? JSONPATH_CONF_LAX : JSONPATH_CONF_STRICT;

                        // Phase 2: State Extraction (Mutate Variables)
                        this.moxter.extractor.extractAndSave(finalSpec, env, mergedVars, moxtureName, jsonPathConfig);

                        // Phase 3: Evaluation (Assertions)
                        try {
                            this.moxter.verifier.verifyExpectations(finalSpec, env, r.baseDir, mergedVars, moxtureName, jsonPathConfig);
                        } catch (AssertionError e) {
                            if (isAllowFailure) {
                                log.warn("[Moxter] (allowFailure) expectation failed for '{}': {}", moxtureName, e.getMessage());
                            } else {
                                throw e;
                            }
                        }
                    }
                    return env;
                } 
                catch (Throwable t) 
                {   if (isAllowFailure) {
                        log.warn("[Moxter] (allowFailure) single moxture '{}' failed — skipping. Cause: {}", moxtureName, t.toString());
                        return null;
                    }
                    if (t instanceof RuntimeException) throw (RuntimeException) t;
                    throw new RuntimeException("Error executing moxture '" + moxtureName + "'", t);
                }
            }
        }


        private static boolean isGroupMoxture(Model.Moxture f) {
            // A group is any moxture that *declares* a moxtures list (even empty → no-op group)
            return f != null && f.getMoxtures() != null;
        }


        /**
         * Blends the runtime Java API overrides into the static YAML moxture options.
         * 
         * Creates a shallow clone to avoid mutating the engine's cached definitions.
         */
        private Model.Moxture blendInRuntimeOptions(Model.Moxture staticMoxt) {
            Model.Moxture finalSpec = MoxResolver.cloneWithoutBasedOn(staticMoxt);
            Model.RootOptionsDef staticOpts = finalSpec.getOptions();
            Model.RootOptionsDef mergedOpts = new Model.RootOptionsDef();
            
            // Priority: Java API (.verbose()) > YAML (options: verbose:) > Default (false)
            
            // Handle Verbose
            Boolean v = this.verbose; // Java API setting
            if (v == null && staticOpts != null) v = staticOpts.getVerbose(); // Fallback to YAML
            mergedOpts.setVerbose(v != null ? v : false); // Fallback to engine default

            // Handle AllowFailure
            Boolean af = this.allowFailure; // Java API setting
            if (af == null && staticOpts != null) af = staticOpts.getAllowFailure(); // Fallback to YAML
            mergedOpts.setAllowFailure(af != null ? af : false); // Fallback to engine default

            if (this.expectedStatusOverride != null) {
                if (finalSpec.getExpect() == null) {
                    finalSpec.setExpect(new Model.ExpectDef());
                }
                // Convert the Object (Integer/String/List) into a JsonNode for the Verifier
                finalSpec.getExpect().setStatus(moxter.jsonMapper.valueToTree(this.expectedStatusOverride));
            }

            finalSpec.setOptions(mergedOpts);
            return finalSpec;
        }
    }



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
    public static class MoxVars 
    {
        private final Moxter moxter;

        // If null then the scope is global
        private final String moxtureName;

        // Cached map of the target(local/global) variables
        private final Map<String, Object> targetMap; 

        // Used for debugging vars in varsDump()
        private static final ObjectMapper VARS_DUMP_MAPPER = new ObjectMapper()
                                                                .enable(SerializationFeature.INDENT_OUTPUT);



        protected MoxVars(Moxter moxter, String moxtureName) {
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
                MoxResolver.EffectiveMoxture resolved = this.moxter.moxResolver.resolveByName(moxtureName);
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

         * - <b>Strict mode enabled:</b> throws an exception if the key already exists.
         * - <b>Strict mode disabled:</b> overwrites and logs a WARN if there was a previous value.

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

        // #############################################################################################

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

            /** 
             * Returns the variable as a String. 
             */
            public String asString() {
                Object target = val;
                // Auto-unboxing for lists of one item
                if (target instanceof List<?> list && list.size() == 1) {
                    target = list.get(0);
                }
                return target == null ? null : String.valueOf(target);
            }

            /** 
             * Returns the variable as a Long, handling Integer/String conversions.
             * @throws IllegalStateException if the variable cannot be parsed as a number
             */
            public Long asLong() {
                Object target = val;
                // Auto-unboxing for lists of one item
                if (target instanceof List<?> list && list.size() == 1) {
                    target = list.get(0);
                }
                if (target == null) return null;
                if (target instanceof Number n) return n.longValue();
                if (target instanceof String s) {
                    try { return Long.parseLong(s); } 
                    catch (NumberFormatException e) {
                        throw new IllegalStateException("Var '" + key + "' cannot be parsed as Long: " + s, e);
                    }
                }
                throw new IllegalStateException("Var '" + key + "' is not a number (actual: " + target.getClass().getSimpleName() + ")");
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

        // #############################################################################################

        /**
         * Map wrapper that represents the combination of:
         *   - <b>Per-call-scoped variable overrides</b> (provided by the caller)
         *   - <b>Global engine variables</b> (owned by Moxter)
         *
         * <p> Semantics:
         *   - <b>Reads (get/containsKey):</b> first consult the scoped overrides;
         *       if the key is not present there, fall back to the global vars.
         *   - <b>Iteration (entrySet/size):</b> presents a merged snapshot view,
         *       with scoped overrides taking precedence on key collisions.
         *   - <b>Writes (put):</b> always delegated to the engine’s global
         *       writing mecanism (via the provided writer) thus using a single 
         *       "source of truth" which exerts overwrite-warning semantics.
         *   - <b>Overrides are immutable:</b> the scoped map is copied on construction
         *       and never mutated by this wrapper.
         *
         * <p> Why not just merge the locally scoped variables to the globals vars into a 
         * temporary map and use that as the call var-context (e.g., 
         * new HashMap(globals).putAll(scoped))?
         * 
         * <p> Unlike a static merged snapshot, this wrapper provides live read-through 
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


    }


    // #############################################################################################

    /**
     * Internal namespace for components that handle the <b>execution phase</b> of a moxture.
     * 
     * <p> Once a moxture definition has been discovered and fully materialized/'effectived' 
     * (flattened), the classes in this namespace take over to perform the actual test execution. 
     * Their responsibilities include:
     *   - <b>Templating:</b> Interpolating dynamic variables into endpoints, headers, and bodys.
     *   - <b>Body Resolution:</b> Parsing and resolving complex or external JSON/YAML request bodies.
     *   - <b>HTTP Execution:</b> Translating the moxture into a Spring MockMvc request and firing it.
     *   - <b>Response Handling:</b> Capturing the raw network response and packaging it into an internal envelope.

     * 
     * <p><b>Note:</b> Classes within this namespace are strictly internal to the Moxter engine 
     * and should never be accessed or instantiated directly by test code.
     */
    static final class MoxRuntime {

        /**
         * A strategy interface for interpolating dynamic variables into strings.
         *
         * <p>This engine is responsible for finding placeholders (e.g., {@code {{varName}}}) 
         * in the moxture definition and replacing them with their actual values from the 
         * provided variable context.
         */
        interface Templating {

            /**
             * The regular expression pattern used to identify variable placeholders within strings.
             * 
             * <p>Supports two distinct syntax styles:
             * - Mustache style: {{variableName}} (Legacy support)
             * - Spring/Maven style: ${variableName}} (Recommended for typo-resilience in YAML
             *   and URIs)
             * 
             * The pattern uses two capture groups to isolate the variable key regardless of the 
             * surrounding markings.
             */
            Pattern DUAL_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}|\\$\\{([^}]+)\\}");

            /**
             * Processes a single string, replacing all variable placeholders with their 
             * corresponding values.
             *
             * @param s    The template string containing placeholders.
             * @param vars The contextual map of variables available for substitution.
             * @return The fully interpolated string, or the original string if no placeholders exist.
             */
            String apply(String s, Map<String, Object> vars);

            /**
             * Processes a map of key-value pairs, applying templating <b>only to the values</b>.
             * 
             * <p>This is particularly useful for HTTP headers and query parameters, where 
             * the keys (e.g., "Authorization" or "page") are static, but the values 
             * (e.g., "Bearer {{token}}" or "{{pageNumber}}") are dynamic.
             *
             * @param in   The original map of static keys and templated values.
             * @param vars The contextual map of variables available for substitution.
             * @return A new map containing the original keys and the fully interpolated values.
             */
            Map<String, String> applyMapValuesOnly(Map<String, String> in, Map<String, Object> vars);

            /**
             * Processes a template string by replacing all recognized placeholders with their 
             * corresponding values from the provided variable context.
             * 
             * <p> <b>Technical Behavior:</b>
             * - <b>Pattern Matching:</b> Scans for both Mustache-style ({@code {{...}}}) and 
             *   Spring-style ({@code ${...}}) tokens.
             * - <b>Whitespace Tolerance:</b> Automatically trims the extracted variable keys 
             *   (e.g., {@code ${ var }} becomes {@code var}).
             * - <b>Persistence on Miss:</b> If a variable is not found in the context, the 
             *   original placeholder string is preserved verbatim to assist in visual debugging 
             *   of the generated request/URI.
             *  - <b>Regex Safety:</b> Employs {@link java.util.regex.Matcher#quoteReplacement(String)} 
             *    to ensure that variable values containing special characters (like '$' or '\') 
             *    do not interfere with the interpolation process.
             * 
             * 
             * @param template  The raw string containing placeholders (e.g., an endpoint URL or JSON value).
             * @param variables The map representing the current variable context (Global + Scoped overrides).
             * @return A fully interpolated string where placeholders have been replaced by literals, 
             *        or the original string if no placeholders were detected or resolved.
             */
            static String interpolate(String template, Map<String, Object> variables) {
                if (template == null || (!template.contains("{{") && !template.contains("${"))) {
                    return template;
                }

                Matcher matcher = DUAL_PATTERN.matcher(template);
                StringBuilder sb = new StringBuilder();

                while (matcher.find()) {
                    // Group 1 is {{...}}, Group 2 is ${...}
                    String key = (matcher.group(1) != null ? matcher.group(1) : matcher.group(2)).trim();

                    // 1. Try to resolve as a dynamic function (mx.func())
                    String dynamicValue = resolveDynamic(key);
                    if (!dynamicValue.equals(key)) {
                        // Match found in DynamicLibrary: use the generated value
                        matcher.appendReplacement(sb, Matcher.quoteReplacement(dynamicValue));
                    }

                    // 2. Fallback to standard variable map
                    else if (variables != null && variables.containsKey(key)) {
                        Object value = variables.get(key);
                        matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(value)));
                    } 
                    
                    // 3. Keep literal for debugging if nothing matches
                    else {
                        matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
                    }
                }
                matcher.appendTail(sb);
                return sb.toString();
            }

            /**
             * Resolves reserved dynamic keywords (prefixed with "mx.") by invoking 
             * corresponding methods in the {@link DynamicLibrary}.
             * 
             * <p>The method uses reflection to find a matching static method name 
             * in the DynamicLibrary. If no match is found, the original keyword is returned 
             * to allow standard variable interpolation to continue.</p>
             * 
             * @param keyword The raw keyword found in the YAML (e.g., "mx.random()")
             * @return The string result of the Java function, or the original keyword if not found.
             */
            static String resolveDynamic(String keyword) {
                if (keyword == null || !keyword.startsWith("mx.")) {
                    return keyword;
                }

                try {
                    // Parse "mx.random()" -> "random"
                    String methodName = keyword
                            .replace("mx.", "")
                            .replace("()", "")
                            .trim();

                    // Locate the method in the library
                    Method method = DynamicLibrary.class.getMethod(methodName);
                    
                    // Invoke the static method (null instance for static calls)
                    return (String) method.invoke(null);

                } catch (NoSuchMethodException e) {
                    // Log a warning or simply return the string if it's not a dynamic call
                    return keyword;
                } catch (Exception e) {
                    throw new RuntimeException("Failed to resolve dynamic keyword: " + keyword, e);
                }
            }

        }

        /**
         * Simple {{var}} or ${var} string substitution. 
         */
        static final class SimpleTemplating implements Templating 
        {
            public String apply(String s, Map<String, Object> vars) 
            {   return Templating.interpolate(s, vars); // Clean delegation
            }

            /**
             *  Only applies templating to values (keeps keys untouched). 
             */
            public Map<String, String> applyMapValuesOnly(Map<String, String> in, Map<String, Object> vars) 
            {   if (in == null || in.isEmpty()) return in;
                Map<String, String> out = new LinkedHashMap<>(in.size());
                for (Map.Entry<String, String> e : in.entrySet()) {
                    out.put(e.getKey(), apply(e.getValue(), vars));
                }
                return out;
            }
        }


        /**
         * A library of plain Java functions accessible within Moxture YAML files via special 
         * keywords.
         * 
         * <p> Any public static method added to this class that returns a {@code String} 
         * can be invoked in a moxture using the "${mx.methodName()}" syntax.
         */
        public class DynamicLibrary {

            /**
             * Generates a short, 8-character random alphanumeric string.
             * 
             * Useful for unique identifiers that don't require full UUID complexity.
             * 
             * @return a random 8-char string (e.g., "7f3k9a21")
             */
            public static String random() {
                return UUID.randomUUID().toString().substring(0, 8);
            }

            /**
             * Generates a full RFC 4122 version 4 UUID.
             * 
             * Use this for fields like channelId or threadId that require strict UUID formats.
             * 
             * @return a 36-character UUID string.
             */
            public static String uuid() {
                return UUID.randomUUID().toString();
            }

            /**
             * Returns the current system time in milliseconds.
             * 
             * Useful for timestamping events or ensuring order in high-velocity tests.
             * 
             * @return current time as a string.
             */
            public static String time() {
                return String.valueOf(System.currentTimeMillis());
            }

            /**
             * Returns the current system time in ISO-8601 UTC format.
             * 
             * Example: "2026-03-17T21:13:53Z"
             * 
             * @return a standardized UTC timestamp string.
             */
            public static String now() {
                return java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                        .format(java.time.format.DateTimeFormatter.ISO_INSTANT);
            }
        }


        // #########################################################################################

        /**
         * Resolves raw body definitions into finalized JSON nodes by processing variable 
         * interpolation, classpath resource loading, and structural recursion.
         * 
         * <p>The resolver handles two primary formats:

         * - <b>String/Scalar:</b> Can be a "classpath:..." reference, a raw JSON string, 
         * or a plain text body. All variables are interpolated before parsing.
         * - <b>Structural:</b> A YAML map or array. The resolver recursively walks 
         * the tree and applies templating to every string leaf node.

         */
        static final class BodyResolver 
        {
            private final ObjectMapper mapper;

            /**
             * @param mapper The Jackson mapper (typically a YAML-aware instance) used 
             * to parse strings and manipulate nodes.
             */
            BodyResolver(ObjectMapper mapper) { this.mapper = mapper; }

            /**
             * The new entry point that handles the inheritance stack.
             * 
             * <p> Resolves the final body by merging the inheritance stack.
             * 
             * <p>This fix ensures thread-safety by creating deep copies of every 
             * layer before merging, preventing runtime variable interpolation 
             * from "poisoning" the shared static cache.</p>
             */
            JsonNode resolve(Model.Moxture spec, String baseDir, Map<String, Object> vars, Templating tpl) throws IOException {
                List<JsonNode> stack = spec.getBodyStack();
                
                if (stack == null || stack.isEmpty()) {
                    JsonNode single = resolveSingleNode(spec.getBody(), baseDir, vars, tpl);
                    // Defensive deepCopy ensures the return value is detached from the cache
                    return (single == null) ? null : single.deepCopy();
                }

                JsonNode effective = null;
                for (JsonNode layer : stack) {
                    // 1. Resolve the layer (handles interpolation/classpath)
                    JsonNode resolvedLayer = resolveSingleNode(layer, baseDir, vars, tpl);
                    
                    if (resolvedLayer == null) continue;

                    // 2. CRITICAL FIX: Ensure the layer is a detached deep copy 
                    // before merging it into the effective result.
                    JsonNode detachedLayer = resolvedLayer.deepCopy();
                    
                    // 3. Deep merge into the accumulated result
                    effective = MoxResolver.deepMergeBody(mapper, effective, detachedLayer);
                }
                return effective;
            }

            /**
             * The main entry point for body resolution.
             * 
             * It handles: Interpolation -> Classpath -> JSON Sniffing -> Parsing
             * 
             * @param body    The raw JsonNode from the YAML moxture definition.
             *                Can be a "classpath:..." to a file containing JSON text.
             * @param baseDir The directory of the current moxture file (used for relative classpath resolution).
             * @param vars    The variable context for interpolation.
             * @param tpl     The templating engine for macro expansion.
             * @return A finalized {@link JsonNode} ready for HTTP execution.
             * @throws IOException If a classpath resource cannot be read or JSON is malformed.
             */
            private JsonNode resolveSingleNode(JsonNode body, String baseDir,
                                               Map<String, Object> vars, Templating tpl) throws IOException 
            {   if (body == null) return null;

                if (body.isTextual()) {
                    String txt = body.asText().trim();
                    
                    // Interpolate variables FIRST (makes the JSON valid!)
                    txt = Templating.interpolate(txt, vars);
                    
                    // Handle 'classpath:'
                    String lower = txt.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("classpath:")) {
                        String rawPath = txt.substring(txt.indexOf(':') + 1).trim();
                        JsonNode fileContent = loadClasspathBody(baseDir, rawPath);
                        return resolveSingleNode(fileContent, baseDir, vars, tpl);
                    }

                    // Heuristic JSON check
                    if (Utils.Json.looksLikeJson(txt)) {
                        try {
                            return mapper.readTree(txt);
                        } catch (JsonProcessingException e) {
                            return mapper.getNodeFactory().textNode(txt);
                        }
                    }
                    return mapper.getNodeFactory().textNode(txt);
                }

                return templateNodeStrings(body, vars, tpl);
            }


            /**
             * Loads a YAML or JSON file from the classpath and returns it as a JsonNode.
             */
            private JsonNode loadClasspathBody(String baseDir, String rawPath) throws IOException {
                String path = rawPath.startsWith("/") ? rawPath.substring(1) : (baseDir + "/" + rawPath);
                URL url = Utils.IO.findResource(path);

                if (url == null) throw new IllegalArgumentException("Resource not found on classpath: " + path);

                try (InputStream in = url.openStream()) {
                    return mapper.readTree(in);
                }
            }


            /**
             * Recursively walks a JSON tree and applies templating to all string leaves. 
             * This ensures that variables inside YAML maps/lists are properly expanded.
             */
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

        // ##########################################################################################################

        /**
         * Strategy interface for executing a moxture across specific transport protocols.
         * 
         * <p>Implementations are responsible for the "Wire" phase of the execution pipeline. 
         * While the {@link MoxCaller} manages orchestration and state, the Executor handles 
         * the mapping between the abstract Moxture model and a concrete physical request.
         * 
         * <p><b>Responsibilities:</b>
         * - <b>Protocol Identification:</b> Determining if it can handle a spec via {@link #supports}.
         * - <b>Templating:</b> Resolving placeholders in endpoints, headers, and payloads using 
         *   the provided variable context.
         * - <b>Payload Resolution:</b> Loading external body files if referenced in the spec.
         * - <b>Execution:</b> Interacting with the underlying mock client (e.g., MockMvc, 
         *   MockStompSession).
         * - <b>Normalization:</b> Capturing the result into a standardized {@link ResponseEnvelope}.
         * 
         * <p><b>Non-Responsibilities:</b>
         * - Executors <b>must not</b> perform assertions or validations (handled by Verifier).
         * - Executors <b>must not</b> persist variables to the context (handled by Extractor).
         * - Executors <b>must not</b> handle {@code basedOn} inheritance (handled by Resolver).
         * 
         */
        interface IProtocolExecutor {
            
            /**
             * Determines if this executor is capable of handling the given moxture.
             * 
             * <p>Usually matches against the {@code protocol} field, but can also inspect 
             * fields like {@code method} or {@code endpoint} to infer the protocol.
             * 
             * @param spec The moxture definition to check.
             * @return true if this executor can process the request.
             */
            boolean supports(Model.Moxture spec);

            /**
             * Translates the abstract moxture into a physical request and captures the response.
             * 
             * @param spec     The fully materialized moxture definition.
             * @param baseDir  The anchor directory for resolving relative file paths.
             * @param vars     The variable context used for string interpolation.
             * @param callAuth Optional Spring Security context to apply to this specific execution.
             * @return A {@link ResponseEnvelope} containing the status, headers, and body.
             * @throws Exception If the underlying transport fails or if the request is malformed.
             */
            ResponseEnvelope execute(Model.Moxture spec, String baseDir, 
                                     Map<String, Object> vars, Authentication callAuth) throws Exception;
        }

        // #########################################################################################

        /**
         * The primary engine responsible for executing Moxture definitions against a MockMvc instance.
         * 
         * <p>The execution lifecycle follows a strict sequence:
         * - <b>Resolution:</b> Variables and templates in the URL, headers, and body are resolved.
         * - <b>Body Preparation:</b> External resources (classpath) are loaded if specified.
         * - <b>Request Construction:</b> A {@link MockHttpServletRequestBuilder} is initialized with 
         *      the appropriate method, URI, and security context (CSRF, Authentication).
         * - <b>Execution:</b> The request is dispatched via {@code MockMvc.perform()}.
         * - <b>State Management:</b> Response data (JSON body, headers) is extracted and stored 
         *       back into the variable context for use by subsequent moxtures.
         * - <b>Validation:</b> Status codes and body contents are asserted against the 
         *       {@code expect} block.
         * 
         */
        @Slf4j
        static final class HttpExecutor implements IProtocolExecutor
        {
            private final MockMvc mockMvc;
            private final ObjectMapper jsonMapper;  // to send the body as JSON
            private final Templating tpl;
            private final BodyResolver bodies;
            private final Supplier<Authentication> authSupplier;

            // Internal session state used to simulate a persistent browser session across multiple 
            // moxture executions.
            // In standard {@link MockMvc} tests, each request is stateless by default. This field 
            // ensures that server-side state—specifically authentication metadata and session-scoped 
            // beans is preserved between sequential calls made by the same executor instance
            //# OLD private final MockHttpSession session = new MockHttpSession();
            // The session needs to be segregated by user (authentication):
            private final Map<String, MockHttpSession> sessionRegistry = new HashMap<>();

            HttpExecutor(MockMvc mockMvc, 
                         ObjectMapper jsonMapper, 
                         Templating tpl,
                         BodyResolver bodies,
                         Supplier<Authentication> authSupplier) {
                this.mockMvc = mockMvc;
                this.jsonMapper = jsonMapper;
                this.tpl = tpl;
                this.bodies = bodies;
                this.authSupplier = authSupplier;
            }

            @Override
            public boolean supports(Model.Moxture spec) {
                String p = spec.getProtocol();
                // If omitted, blank, or explicitly http/https, this executor handles it
                return p == null || p.isBlank() || p.equalsIgnoreCase("http") || p.equalsIgnoreCase("https");
            }

            /**
             * Execute a single moxture call.
             * 
             * <p>Logs a concise start line, rich DEBUG details, response preview, a finish line with duration,
             * and a compact warning when the expected status does not match.
             */
            @Override
            public MoxRuntime.ResponseEnvelope execute(Model.Moxture spec, String baseDir, 
                                                       Map<String,Object> vars, 
                                                       Authentication callAuth)
            {
                final long t0 = System.nanoTime();
                final String name   = (spec.getName() == null || spec.getName().isBlank()) ? "<unnamed>" : spec.getName();
                final String method = Utils.Http.safeMethod(spec.getMethod());
                final boolean verbose = spec.getOptions() != null && Boolean.TRUE.equals(spec.getOptions().getVerbose());

                try {
                    // 0. Resolve the actual Actor for this call
                    Authentication auth = resolveAuthentication(callAuth);
                    String actorKey = (auth != null) ? auth.getName() : "ANONYMOUS";

                    // 0.1 Get or create the session for THIS specific Actor
                    MockHttpSession actorSession = sessionRegistry.computeIfAbsent(
                        actorKey, k -> new MockHttpSession()
                    );

                    // 1. Prepare URI, Headers, and Body
                    if (spec.getEndpoint() == null || spec.getEndpoint().isBlank()) {
                        throw new IllegalArgumentException("Moxture '" + name + "' has no 'endpoint'.");
                    }
                    final String endpoint = tpl.apply(spec.getEndpoint(), vars);
                    final Map<String,String> headers0 = tpl.applyMapValuesOnly(spec.getHeaders(), vars);
                    final Map<String,String> query    = tpl.applyMapValuesOnly(spec.getQuery(), vars);
                    final URI uri = URI.create(Utils.Http.appendQuery(endpoint, query));
                    final JsonNode bodyNode = bodies.resolve(spec, baseDir, vars, tpl);

                    logExecutionStart(verbose, name, method, uri, headers0, query, vars, bodyNode);

                    // 2. Build the Spring MockMvc Request
                    MockHttpServletRequestBuilder req = buildRequest(spec, baseDir, vars, method, uri, headers0, bodyNode, callAuth);
                    req.session(actorSession); // Forces MockMvc to reuse the same session

                    // 3. Execute HTTP Call & Parse Response
                    ResultActions actions = mockMvc.perform(req);
                    // Only trigger the heavy MockMvc print if explicitly requested in YAML options
                    if (verbose) {
                        actions.andDo(print());
                    }
                    MockHttpServletResponse mvcResp = actions.andReturn().getResponse();
                    MoxRuntime.ResponseEnvelope env = parseResponse(mvcResp, method, uri);

                    logResponsePreview(verbose, env);
                    logExecutionEnd(name, method, uri, env.status(), t0);
                    
                    return env;

                } catch (RuntimeException re) {
                    log.warn("[Moxter] X [{}] {} failed: {}", method, name, Utils.Misc.rootMessage(re));
                    throw re;
                } catch (Exception e) {
                    log.warn("[Moxter] X [{}] {} errored: {}", method, name, Utils.Misc.rootMessage(e));
                    throw new RuntimeException("Error executing moxture '" + name + "'", e);
                }
            }

            /**
             * The specific Spring Security Authentication bound to this individual moxture caller.
             * 
             * <p><b>Resolution Priority:</b> When the authentication is set 
             * (via {@link #withAuth(Authentication)}), it takes absolute precedence for the 
             * duration of this caller's life. It effectively shadows:
             * - The engine-level {@code authSupplier} (the default identity for this Moxter instance)
             * - The global {@code SecurityContextHolder} (the standard Spring Security context)
             * 
             * <p>This isolation ensures that a specific user (e.g., User B) can attempt actions 
             * without permanently mutating the thread's global security state or the engine's 
             * default user.</p>
             */
            private Authentication resolveAuthentication(Authentication callAuth) {
                // 1. Highest Priority: The Call-Scoped Override (from mx.caller().withAuth(...))
                if (callAuth != null) return callAuth;
                
                // 2. Fallback: The Engine-Scoped Supplier (from Moxter.forTestClass().authentication(...))
                if (authSupplier != null) {
                    try { return authSupplier.get(); } catch (Exception ignore) {}
                }
                
                // 3. Last Resort: The Global Thread Context
                return SecurityContextHolder.getContext().getAuthentication();
            }


            /**
             * Phase 1: Request Building
             */
            private MockHttpServletRequestBuilder buildRequest(Model.Moxture spec, String baseDir, Map<String,Object> vars, 
                                                               String method, URI uri, Map<String,String> headers0, 
                                                               JsonNode bodyNode,
                                                               Authentication callAuth) throws Exception 
            {
                MockHttpServletRequestBuilder req;
                Map<String,String> headers = (headers0 == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(headers0);

                if (spec.getMultipart() != null && !spec.getMultipart().isEmpty()) {
                    req = buildMultipartRequest(spec, baseDir, vars, method, uri, headers);
                } else {
                    req = Utils.Http.toRequestBuilder(method, uri);
                    if (bodyNode != null) {
                        req.content(jsonMapper.writeValueAsBytes(bodyNode));
                        if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
                            req.contentType(MediaType.APPLICATION_JSON);
                        }
                    }
                }

                // Attach Auth & CSRF
                attachSecurity(req, method, callAuth);

                // Attach standard headers
                for (Map.Entry<String,String> e : headers.entrySet()) {
                    req.header(e.getKey(), e.getValue());
                }
                return req;
            }

            /**
             * Phase 1 sub: Multipart Request Building
             */
            private MockHttpServletRequestBuilder buildMultipartRequest(Model.Moxture spec, String baseDir, Map<String,Object> vars, 
                                                                      String method, URI uri, Map<String,String> headers) throws Exception 
            {
                log.debug("[Moxter] Multipart detected");
                headers.remove(HttpHeaders.CONTENT_TYPE);
                headers.remove("Content-Type");

                MockMultipartHttpServletRequestBuilder multiReq = MockMvcRequestBuilders.multipart(uri);
                String effectiveMethod = (method == null) ? "POST" : method.toUpperCase(Locale.ROOT);
                multiReq.with(r -> { r.setMethod(effectiveMethod); return r; });

                for (Model.MultipartDef part : spec.getMultipart()) {
                    String pName = tpl.apply(part.name, vars);
                    String pType = (part.type != null) ? part.type.toLowerCase() : "json";
                    String pFilename = tpl.apply(part.filename, vars);

                    byte[] contentBytes;
                    String contentType;

                    if ("file".equals(pType)) {
                        String path = part.body.asText();
                        if (path.toLowerCase().startsWith("classpath:")) path = path.substring(10).trim();
                        contentBytes = Utils.IO.readResourceBytes(baseDir, tpl.apply(path, vars));
                        contentType = (pFilename != null && pFilename.endsWith(".pdf")) ? "application/pdf"
                                    : (pFilename != null && pFilename.endsWith(".png")) ? "image/png"
                                    : "application/octet-stream";
                    } else {
                        JsonNode resolvedPartBody = bodies.resolveSingleNode(part.body, baseDir, vars, tpl);
                        if ("json".equals(pType) || resolvedPartBody.isContainerNode()) {
                            contentBytes = jsonMapper.writeValueAsBytes(resolvedPartBody);
                            contentType = "application/json";
                            if (pFilename == null) pFilename = "";
                        } else {
                            contentBytes = resolvedPartBody.asText().getBytes(StandardCharsets.UTF_8);
                            contentType = "text/plain";
                        }
                    }
                    multiReq.file(new org.springframework.mock.web.MockMultipartFile(pName, pFilename, contentType, contentBytes));
                }
                return multiReq;
            }

            private void attachSecurity(MockHttpServletRequestBuilder req, String method, org.springframework.security.core.Authentication callAuth) {
                
                // 1. Highest Priority: The Call-Scoped Override (from mx.caller().withAuthentication(...))
                Authentication auth = callAuth;
                
                // 2. Fallback (The Default): The Engine-Scoped Supplier (from Moxter.forTestClass().authentication(...))
                if (auth == null && authSupplier != null) {
                    try { auth = authSupplier.get(); } catch (Exception ignore) {}
                }
                
                // 3. Last Resort: The Global Thread Context (Standard Spring Security)
                if (auth == null) {
                    auth = SecurityContextHolder.getContext().getAuthentication();
                }
                
                // Apply the resolved authentication to the mock request
                if (auth != null) {
                    req.with(SecurityMockMvcRequestPostProcessors.authentication(auth));
                    log.debug("[Moxter] using Authentication principal={}", Utils.Misc.safeName(auth));
                }
                
                // Attach CSRF token for state-changing methods
                if (Utils.Http.requiresCsrf(method)) {
                    req.with(SecurityMockMvcRequestPostProcessors.csrf());
                    log.debug("[Moxter] CSRF token added for {}", method);
                }
            }

            /**
             * Phase 2: Response Parsing
             */
            private MoxRuntime.ResponseEnvelope parseResponse(MockHttpServletResponse mvcResp, String method, URI uri) throws Exception {
                final String raw = mvcResp.getContentAsString(StandardCharsets.UTF_8);
                final String ctHeader = mvcResp.getHeader(HttpHeaders.CONTENT_TYPE);
                final boolean hasBody   = raw != null && !raw.isBlank();
                final boolean isJsonCT  = Utils.Http.isJsonContentType(ctHeader);
                final boolean looksJson = hasBody && Utils.Json.looksLikeJson(raw);

                JsonNode body = null;
                if (hasBody && (isJsonCT || looksJson)) {
                    try { body = jsonMapper.readTree(raw); } 
                    catch (Exception parseEx) {
                        log.debug("[Moxter] Non-JSON body (ct='{}') could not be parsed: {}", ctHeader, Utils.Misc.rootMessage(parseEx));
                    }
                }
                
                // Pack the method and URI into the envelope alongside the response data
                return new MoxRuntime.ResponseEnvelope(method, uri, mvcResp.getStatus(), Utils.Http.copyHeaders(mvcResp), body, raw);
            }


            /**
             * Phase 4: Save & Logging Helpers
             */
            private void logExecutionStart(boolean verbose, String name, String method, URI uri, 
                                           Map<String,String> headers, Map<String,String> query, 
                                           Map<String,Object> vars, JsonNode body) 
            {
                log.info("[Moxter] >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
                log.info("[Moxter] >>> Executing moxture:  [{}, {}, {}]", name, method, uri);
                
                if (verbose || log.isDebugEnabled()) {
                    String msg = "[Moxter] more info: headers={} query={} vars={} body={}";
                    Object[] args = {
                            Utils.Logging.previewHeaders(headers),
                            (query == null || query.isEmpty() ? "{}" : query.toString()),
                            Utils.Logging.previewVars(vars),
                            Utils.Logging.previewNode(body)
                    };
                    // The bypass: if verbose is requested, force it out at INFO level
                    if (verbose) log.info(msg, args); else log.debug(msg, args);
                }
            }

            private void logResponsePreview(boolean verbose, MoxRuntime.ResponseEnvelope env) {
                if (verbose || log.isDebugEnabled()) {
                    String msg = "[Moxter] response preview: status={} headers={} body={}";
                    Object[] args = { env.status(), Utils.Logging.previewRespHeaders(env.headers()), Utils.Logging.previewNode(env.body()) };
                    
                    if (verbose) log.info(msg, args); else log.debug(msg, args);
                }
                if (verbose || log.isTraceEnabled()) {
                    String msg = "[Moxter] Raw body (len={}): {}";
                    Object[] args = { env.raw() == null ? 0 : env.raw().length(), Utils.Logging.truncate(env.raw(), 4000) };
                    
                    if (verbose) log.info(msg, args); else log.trace(msg, args);
                }
            }

            private void logExecutionEnd(String name, String method, URI uri, int status, long startTimeNano) {
                long tookMs = (System.nanoTime() - startTimeNano) / 1_000_000L;
                log.info("[Moxter] <<< Finished executing moxture: [{}, {}, {}] with status: [{}], in {} ms", name, method, uri, status, tookMs);
                log.info("[Moxter] <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<");
            }

        }

        // #########################################################################################

         /**
         * Executor for the STOMP protocol that bridges Moxter moxtures with the 
         * <b>MockWebs</b> testing infrastructure.
         * 
         * <p>This class serves as the functional equivalent of the {@link HttpExecutor}. 
         * Just as the {@code HttpExecutor} uses {@code MockMvc} to simulate REST 
         * interactions, this executor uses <b>MockWebs</b> to perform real-time, 
         * in-memory STOMP operations within the Spring application context.</p>
         * 
         * <p><b>Execution Logic:</b>
         * - <b>Dispatch:</b> It translates the moxture's {@code endpoint} into a STOMP destination
         *   and sends the {@code body} payload through the <b>MockWebs</b> client.
         * - <b>Normalization:</b> While HTTP is naturally synchronous, with a response to the
         *   request, STOMP is often fire-and-forget. To maintain compatibility with the 
         *   {@link ExpectVerifier}, it captures the outbound frame and wraps it in a 
         *   {@link ResponseEnvelope}.
         * - <b>Verification:</b> This "Loopback" behavior allows the user to perform standard 
         *   JsonPath assertions (via the {@code expect} block) against the final message content 
         *   as it was seen by the messaging subsystem.
         * 
         * * @see IProtocolExecutor
         */
        @Slf4j
        static final class StompExecutor implements IProtocolExecutor {
            
            private final MockWebs mockWebs;
            private final ObjectMapper jsonMapper;
            private final BodyResolver bodies;
            private final Templating tpl;
            private final Supplier<Authentication> authSupplier;

            /**
             * Constructs a new StompExecutor with shared engine helpers.
             * 
             * @param jsonMapper  Mapper for serializing message payloads.
             * @param bodies      Resolver for processing {@code body} and {@code classpath:} resources.
             * @param tpl         Templating engine for variable interpolation in destinations.
             */
            StompExecutor(MockWebs mockWebs,
                          ObjectMapper jsonMapper, 
                          Templating tpl,
                          BodyResolver bodies,
                          Supplier<Authentication> authSupplier
                        ) 
            {   this.mockWebs = mockWebs;
                this.jsonMapper = jsonMapper;
                this.bodies = bodies;
                this.tpl = tpl;
                this.authSupplier = authSupplier;
            }

            /**
             * Determines if the moxture explicitly requests the STOMP protocol.
             * 
             * @param spec The moxture definition.
             * @return {@code true} if the protocol is "stomp" (case-insensitive).
             */
            @Override
            public boolean supports(Model.Moxture spec) {
                return spec.isProtocolStomp();
            }

            /**
             * Translates the Moxture into a STOMP SEND frame and captures the 
             * transmission result.
             * 
             * @param spec     The materialized moxture definition.
             * @param baseDir  Anchor directory for payload resource resolution.
             * @param vars     Variable context for destination and payload interpolation.
             * @param callAuth Optional authentication context.
             * @return A {@link ResponseEnvelope} containing the delivery confirmation.
             * @throws Exception If payload resolution or serialization fails.
             */
            @Override
            public ResponseEnvelope execute(Model.Moxture spec, String baseDir, 
                                            Map<String, Object> vars, 
                                            Authentication callAuth) throws Exception {
                final long t0 = System.nanoTime();
                final String name = (spec.getName() == null) ? "<unnamed>" : spec.getName();
                final boolean verbose = spec.getOptions() != null && Boolean.TRUE.equals(spec.getOptions().getVerbose());

                // 1. SEAMLESS IDENTITY RESOLUTION (Mirroring HttpExecutor)
                // Hierarchy: Call-Scoped > Engine-Scoped > Global Context
                Authentication auth = callAuth;
                if (auth == null && authSupplier != null) {
                    try { auth = authSupplier.get(); } catch (Exception ignore) {}
                }
                if (auth == null) {
                    auth = SecurityContextHolder.getContext().getAuthentication();
                }

                // 2. Resolve Destination and Payload
                if (spec.getEndpoint() == null || spec.getEndpoint().isBlank()) {
                    throw new IllegalArgumentException("STOMP moxture '" + name + "' has no 'endpoint' (destination).");
                }
                String destination = tpl.apply(spec.getEndpoint(), vars);
                JsonNode payload = bodies.resolve(spec, baseDir, vars, tpl);

                // Start Logging
                log.info("[Moxter] >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
                log.info("[Moxter] >>> Executing STOMP moxture: [{}, SEND, {}]", name, destination);
                log.info("[Moxter] SEND destination: {}");
                log.info("[Moxter] payload: {}", Utils.Logging.previewNode(payload));
                
                if (verbose) {
                    log.info("[Moxter] identity: {}", Utils.Misc.safeName(auth));
                }

                // 3. EXECUTION: Dispatch via MockWebs session
                // This bridges Moxter's identity to the user session, mirroring:
                MockWebs.StompSession session = mockWebs.with(auth);
                Object result = session.send(destination, payload); 

                // 4. NORMALIZATION & TIMING
                // We treat the dispatched content/result as the "response" for verification
                String rawResponse = (result instanceof byte[] bytes) ? new String(bytes) : jsonMapper.writeValueAsString(result);
                long tookMs = (System.nanoTime() - t0) / 1_000_000L;
                
                log.info("[Moxter] <<< Finished executing STOMP moxture: [{}] in {} ms", name, tookMs);
                log.info("[Moxter] <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<");

                return new ResponseEnvelope(
                    "SEND", 
                    URI.create("stomp://" + destination), 
                    200, 
                    Collections.emptyMap(), 
                    jsonMapper.valueToTree(result), 
                    rawResponse
                );
            }
        }

        // #########################################################################################

        /**
         * An internal, immutable container representing the raw HTTP response returned by 
         * the execution engine.
         *
         * <p>This class acts as a bridge between the underlying HTTP client (Spring MockMvc) 
         * and Moxter's public API. It captures the exact state of the network response 
         * immediately after execution, before it is wrapped in a user-friendly 
         * {@link MoxtureResult} for assertions.
         *
         * @param method  The HTTP method executed (e.g., "POST").
         * @param uri     The fully resolved target URI.
         * @param status  The HTTP status code returned by the server (e.g., 200, 404).
         * @param headers The HTTP response headers.
         * @param body    The response body parsed into a Jackson JSON tree (null if not JSON).
         * @param raw     The raw, unparsed string representation of the HTTP response body.
         */
        public record ResponseEnvelope(
            String method,
            URI uri,
            int status, 
            Map<String, List<String>> headers, 
            JsonNode body, 
            String raw
        ) {}

        // #########################################################################################

        /**
         * Responsible strictly for extracting values from the network response and saving them 
         * into the variable context.
         * 
         * <p>This component mutates the running state of the test. It runs before assertions 
         * to ensure that even if a moxture fails (and 'allowFailure' is true), any successfully 
         * generated IDs or tokens are still captured for subsequent steps.
         */
        @Slf4j
        static final class VarExtractor {
            
            /**
             * Evaluates the 'save' block of a moxture using JsonPath.
             * 
             * @param spec           The moxture definition containing the 'save' map.
             * @param env            The network response envelope.
             * @param vars           The live variable context where extracted values will be written.
             * @param name           The name of the current moxture (used for logging).
             * @param jsonPathConfig The JsonPath configuration (strict or lax).
             */
            public void extractAndSave(Model.Moxture spec, MoxRuntime.ResponseEnvelope env, 
                                       Map<String,Object> vars, String name, 
                                       Configuration jsonPathConfig) 
            {
                if (spec.getSave() != null && !spec.getSave().isEmpty()) {
                    if (env.body() != null) {
                        DocumentContext ctx = JsonPath.using(jsonPathConfig).parse(env.raw());
                        for (Map.Entry<String, String> e : spec.getSave().entrySet()) {
                            vars.put(e.getKey(), ctx.read(e.getValue()));
                        }
                        log.debug("[Moxter] saved vars from '{}': {}", name, spec.getSave().keySet());
                    }
                }
            }
        }

        // #########################################################################################

        /**
         * Protocol-agnostic evaluation engine responsible for processing the 'expect' 
         * directives from a moxture definition against a standardized HTTP/STOMP/... response envelope.
         * 
         * <p>By separating the evaluation logic from the network dispatchers (executors), 
         * Moxter ensures that assertions behave identically regardless of the underlying 
         * transport protocol. This class acts as the final gatekeeper in the moxture lifecycle.
         */
        @Slf4j
        static final class ExpectVerifier 
        {
            private final BodyResolver bodyResolver;
            private final ObjectMapper jsonMapper;
            private final Templating tpl;

            // We'll need it for STOMP moxtures to verify the expect.broadcast 
            private final MockWebs mockWebs;

            /**
             * Constructs a new verifier with the necessary tools to interpolate strings, 
             * parse JSON, and evaluate HTTP statuses.
             * 
             * @param bodyResolver  Engine to resolve expected JSON payloads (including classpath imports).
             * @param jsonMapper    Jackson mapper for generic JSON tree manipulations.
             * @param tpl           Templating engine for variable interpolation.
             * @param mockWebs      We'll need it for STOMP moxtures to verify the expect.broadcast
             *                      If we're doing just HTTP, pass null.
             */
            ExpectVerifier(BodyResolver bodyResolver, 
                           ObjectMapper jsonMapper,
                           Templating tpl,
                           MockWebs mockWebs
                        ) {
                this.bodyResolver = Objects.requireNonNull(bodyResolver, "bodyResolver");
                this.jsonMapper   = Objects.requireNonNull(jsonMapper, "jsonMapper");
                this.tpl          = Objects.requireNonNull(tpl, "tpl");
                this.mockWebs     = mockWebs; // Null is acceptable here
            }

            /**
             * The primary entry point for response validation. Orchestrates the evaluation of 
             * status codes, full JSON body matching, and surgical JsonPath assertions.
             * 
             * @param spec           The moxture definition containing the 'expect' block.
             * @param env            The network response envelope to be evaluated.
             * @param baseDir        The anchor directory for resolving any expected classpath payloads.
             * @param vars           The variable context used to template expected values.
             * @param name           The name of the moxture (for error reporting).
             * @param jsonPathConfig The JsonPath configuration (strict or lax).
             * @throws Exception     If a systemic parsing error occurs.
             * @throws AssertionError If any expectation is violated.
             */
            public void verifyExpectations(Model.Moxture spec, MoxRuntime.ResponseEnvelope env, 
                                           String baseDir, Map<String,Object> vars, 
                                           String name, Configuration jsonPathConfig) throws Exception 
            {
                if (spec.getExpect() == null) return;

                // 1. Check Status
                verifyStatus(spec, env, name);

                // 2. Check Body Assertions
                Model.ExpectBodyDef bodyDef = spec.getExpect().getBody();
                if (bodyDef != null)
                {
                    // 2a. Match
                    if (bodyDef.getMatch() != null) {
                        verifyBodyMatch(bodyDef.getMatch(), env, baseDir, vars, name, jsonPathConfig);
                    }

                    // 2b. Assert
                    if (bodyDef.getAssertDef() != null && !bodyDef.getAssertDef().isEmpty()) {
                        // We pass the 'spec' down so it can read the allowFailure flag for the Hybrid Short-Circuit
                        verifyBodyAsserts(spec, bodyDef.getAssertDef(), env, baseDir, vars, jsonPathConfig);
                    }
                }

                // 3. Verify Asynchronous Side-Effects (Broadcasts)
                // Only executed if 'expect.broadcast' is present in the YAML.
                verifyBroadcast(spec, vars);
            }

            /**
             * For verification of the status of the response (expect.status).
             * 
             * <p>Evaluates the actual HTTP status code of the response against the expected 
             * status defined in the moxture.
             * 
             * <p>This method leverages the internal {@code StatusMatcher} to support flexible 
             * status definitions in the YAML file. 
             * 
             * <p>Supported expected formats include:
             * - Exact integers (e.g., 200, 404)
             * - Wildcard strings (e.g., "2xx", "4xx")
             * - Arrays of acceptable statuses (e.g., [200, 201, 204])
             * 
             * <p>If the status does not match, it throws an {@code AssertionError} equipped with a 
             * highly contextual message, including the HTTP method, URI, and a truncated 
             * preview of the actual response body to assist with rapid debugging.
             *
             * @param spec   The moxture definition containing the 'expect.status' block.
             * @param env    The network response envelope containing the actual status and raw body.
             * @param name   The name of the current moxture (for error reporting).
             * @param method The HTTP method used during execution (for error reporting).
             * @param uri    The target URI that was executed (for error reporting).
             * @throws AssertionError If the actual HTTP status does not satisfy the expected criteria.
             */
            private void verifyStatus(Model.Moxture spec, MoxRuntime.ResponseEnvelope env, String name) {
                if (spec.getExpect().getStatus() != null) {
                    if (!statusMatches(spec.getExpect().getStatus(), env.status())) {
                        String bodyPreview = (env.raw() == null || env.raw().isBlank()) ? "<empty>" : Utils.Logging.truncate(env.raw(), 500);
                        throw new AssertionError(String.format(Locale.ROOT, 
                            "Unexpected HTTP %d for '%s' %s %s, expected=%s. Body=%s",
                            env.status(), name, env.method(), env.uri(), 
                            expectedStatusPreview(spec.getExpect().getStatus()), bodyPreview));
                    }
                }
            }

            /**
             * For verification of the assertion on the content of the response body (expect.body.match)
             * 
             * <p>Performs a structural comparison between the expected JSON body and the actual 
             * response body.
             * 
             * <p>Features:
             * - Resolves external JSON payloads from the classpath if specified.
             * - Removes ignored paths dynamically before comparison.
             * - Supports Strict Mode ("full") and Extensible Mode (default - actual JSON can have extra fields).
             * 
             * @param matchDef       The specification defining the expected JSON and ignore paths.
             * @param env            The network response envelope.
             * @param baseDir        The anchor directory.
             * @param vars           The variable context for interpolating the expected JSON.
             * @param name           The moxture name.
             * @param jsonPathConfig The JsonPath configuration used to delete ignored paths.
             * @throws Exception     If I/O fails or JSON is malformed.
             */
            private void verifyBodyMatch(Model.ExpectBodyMatchDef matchDef, MoxRuntime.ResponseEnvelope env, 
                                         String baseDir, Map<String,Object> vars, 
                                         String name, Configuration jsonPathConfig) throws Exception 
            {
                try {
                    if (env.raw() == null || env.raw().isBlank()) throw new AssertionError("Response body is empty.");
                    
                    // We resolve the expected block through the BodyResolver
                    // Using a dummy moxture to reuse BodyResolver logic for a single node:
                    Model.Moxture dummy = new Model.Moxture();
                    dummy.setBody(matchDef.getContent());
                    JsonNode expectedNodeResolved = bodyResolver.resolve(dummy, baseDir, vars, tpl);
                    
                    String expectedJsonStr = jsonMapper.writeValueAsString(expectedNodeResolved);
                    String actualJsonStr = env.raw();

                    if (matchDef.getIgnorePaths() != null && !matchDef.getIgnorePaths().isEmpty()) 
                    {   DocumentContext actCtx = JsonPath.using(jsonPathConfig).parse(actualJsonStr);
                        DocumentContext expCtx = JsonPath.using(jsonPathConfig).parse(expectedJsonStr);
                        for (String path : matchDef.getIgnorePaths()) {
                            try { actCtx.delete(path); } catch (Exception ignore) {}
                            try { expCtx.delete(path); } catch (Exception ignore) {}
                        }
                        actualJsonStr = actCtx.jsonString();
                        expectedJsonStr = expCtx.jsonString();
                    }

                    boolean strictMode = "full".equalsIgnoreCase(matchDef.getMode());
                    JSONAssert.assertEquals(expectedJsonStr, actualJsonStr, strictMode);
                } catch (AssertionError e) {
                    // Wrap the error with context, but ALWAYS throw. Top-level execute() decides whether to swallow it.
                    throw new AssertionError(String.format("Moxture '%s' JSON match failed: %s", name, e.getMessage()), e);
                }
            }

            /**
             * For verification of the individual assertions on the response body 
             * (expect.body.assert.<list of JsonPaths and their expected value>)
             * 
             * <p>Iterates through a map of JsonPath expressions and asserts their values against
             * the response body.
             *
             * <p>Features:
             * - Automatically infers the data type (Boolean, Number, String, Container) and applies 
             *   the correct AssertJ matcher.
             * - Collects all assertion failures into a single compiled error report (Strict mode).
             * - Implements the "Hybrid Short-Circuit": If the moxture has {@code allowFailure: true}, 
             *   it fails fast on the first error to avoid noisy logs and wasted CPU cycles.
             * 
             * @param spec           The moxture definition (used to read the allowFailure flag).
             * @param assertDef      The map of "$.path" -> "expectedValue".
             * @param env            The response envelope.
             * @param baseDir        The anchor directory.
             * @param vars           The variable context.
             * @param jsonPathConfig The JsonPath configuration.
             * @throws Exception     If path evaluation fails fundamentally.
             */
            private void verifyBodyAsserts(Model.Moxture spec, Model.ExpectBodyAssertDef assertDef, 
                                               MoxRuntime.ResponseEnvelope env, String baseDir, 
                                               Map<String,Object> vars, Configuration jsonPathConfig) throws Exception 
            {
                // Read policy from the spec for the short-circuit logic
                final boolean allowFailure = spec.getOptions() != null 
                    && Boolean.TRUE.equals(spec.getOptions().getAllowFailure());

                DocumentContext actCtx = com.jayway.jsonpath.JsonPath.using(jsonPathConfig).parse(env.raw());
                List<String> collectedErrors = new ArrayList<>();
                
                // Using a standard for-loop to cleanly handle exceptions and control flow
                for (Map.Entry<String, JsonNode> entry : assertDef.getPaths().entrySet()) {
                    String path = entry.getKey();
                    JsonNode rawExpectedNode = entry.getValue();
                    
                    try {
                        Model.Moxture dummy = new Model.Moxture();
                        dummy.setBody(rawExpectedNode);
                        JsonNode expectedNode = bodyResolver.resolve(dummy, baseDir, vars, tpl);
                        
                        Object actualValue = actCtx.read(path);
                        
                        if (expectedNode.isNull()) {
                            Assertions.assertThat(actualValue).as("Path '%s'", path).isNull();
                        } else if (expectedNode.isNumber()) {
                            Assertions.assertThat(((Number) actualValue).doubleValue())
                                    .as("Path '%s'", path).isEqualTo(expectedNode.asDouble());
                        } else if (expectedNode.isBoolean()) {
                            Assertions.assertThat(actualValue)
                                    .as("Path '%s'", path).isEqualTo(expectedNode.asBoolean());
                        } else if (expectedNode.isContainerNode()) {
                            String expectedJson = jsonMapper.writeValueAsString(expectedNode);
                            String actualJson = jsonMapper.writeValueAsString(actualValue);
                            org.skyscreamer.jsonassert.JSONAssert.assertEquals(expectedJson, actualJson, true);
                        } else {
                            Assertions.assertThat(String.valueOf(actualValue))
                                    .as("Path '%s'", path).isEqualTo(expectedNode.asText());
                        }
                        
                    } catch (com.jayway.jsonpath.PathNotFoundException e) {
                        collectedErrors.add(String.format("Path '%s' was not found in the response.", path));
                    } catch (AssertionError e) {
                        collectedErrors.add(e.getMessage());
                    } catch (Exception e) {
                        // Infra/Parsing errors still throw immediately, as the JSON/YAML itself is broken
                        throw new RuntimeException("Failed to evaluate assert for path: " + path, e);
                    }

                    // THE HYBRID SHORT-CIRCUIT
                    // If best-effort (allowFailure = true), fail-fast to avoid noisy logs and wasted cycles.
                    if (allowFailure && !collectedErrors.isEmpty()) {
                        throw new AssertionError(collectedErrors.get(0));
                    }
                }

                // If strict mode, we throw the beautifully compiled list of ALL errors.
                if (!collectedErrors.isEmpty()) {
                    throw new AssertionError("Multiple surgical assertions failed:\n- " + String.join("\n- ", collectedErrors));
                }
            }


            private static String expectedStatusPreview(JsonNode expected) {
                if (expected == null || expected.isNull()) return "(none)";
                return expected.toString();
            }


            /**
             * @param expected  Can be any of:
             *                    null | int | "2xx"/"3xx"/"4xx"/"5xx" | "201" | "2xx"  ...
             */
            private boolean statusMatches(JsonNode expected, int actual) {
                if (expected == null || expected.isNull()) return true; // optional

                // allow arrays → any element matching is accepted
                if (expected.isArray()) {
                    for (JsonNode e : expected) {
                        if (statusMatches(e, actual)) {
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

            /**
             * Verifies that a message was broadcasted to a specific STOMP topic.
             * 
             * <p>This method implements the asynchronous side-effect verification. It resolves 
             * the topic name using the current variable context (supporting dynamic paths 
             * like {@code /topic/{{id}}}) and delegates the assertion to the 
             * {@link MockWebs} engine.
             * 
             * @param expect The expectation definition containing the broadcast details.
             * @param vars   The variable context (typically {@link CallScopedVars}) used 
             *               to interpolate the topic name.
             * @throws AssertionError if the broadcast was expected but did not occur.
             * @throws IllegalStateException if configuration is missing.
            */
            private void verifyBroadcast(Model.Moxture spec, Map<String, Object> vars) 
            {
                // 1. Double Guard: Ensure both the 'expect' block and the 'broadcast' block exist
                if (spec.getExpect() == null || spec.getExpect().getBroadcast() == null) return;
                
                Model.ExpectBroadcastDef br = spec.getExpect().getBroadcast();
                if (br.getTopic() == null) return;

                // 2. Protocol Guard: Using the self-aware Model helper
                if (!spec.isProtocolStomp()) {
                    log.warn("[Moxter] Moxture '{}' (protocol: {}) defines 'expect.broadcast' which is only valid for STOMP. Skipping.", 
                             spec.getName(), (spec.getProtocol() == null ? "http" : spec.getProtocol()));
                    return;
                }

                // 3. Dependency Guard: Verify the required tool is available
                if (this.mockWebs == null) {
                    throw new IllegalStateException(String.format(
                        "Moxture '%s' requires broadcast verification, but MockWebs was not provided in the builder.", 
                        spec.getName()));
                }

                // 4. Resolve Topic and Type
                String resolvedTopic = tpl.apply(br.getTopic(), vars);
                Class<?> payloadClass = "String".equalsIgnoreCase(br.getType()) ? String.class : byte[].class;

                // 5. Execution & Contextual Error Handling
                log.info("[Moxter] Verifying broadcast to topic: {}", resolvedTopic);
                try {
                    mockWebs.verifyBroadcast(resolvedTopic, payloadClass);
                } catch (AssertionError e) {
                    throw new AssertionError(String.format("Moxture '%s' failed broadcast verification on topic [%s]: %s", 
                                             spec.getName(), resolvedTopic, e.getMessage()), e);
                } catch (Exception e) {
                    throw new RuntimeException("Internal error during broadcast verification for: " + resolvedTopic, e);
                }
            }

        }
    }

    // #############################################################################################

    /**
     * Represents the outcome of a moxture execution.
     * Provides a fluent interface for inspecting the response and extracting data.
     */
    public static class MoxtureResult 
    {
        private final MoxRuntime.ResponseEnvelope envelope;
        private final String moxtureName;
        // Reference to the encompassing engine
        private final Moxter moxter;

        /**
         * @param envelope    The response wrapper containing status, body, and raw content.
         * @param moxtureName The name of the moxture that produced this result (for error context).
         * @param moxter      The encompassing Engine.
         */
        public MoxtureResult(MoxRuntime.ResponseEnvelope envelope, Moxter moxter, String moxtureName) {
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
        public MoxRuntime.ResponseEnvelope getEnvelope() {
            return envelope;
        }

        /**
         * Extracts a raw value from the response body using a JsonPath expression.
         * 
         * <p> This is a terminal method used to retrieve data from the response that 
         * hasn't necessarily been saved in the Moxter variable context.
         * 
         * <p> <b>Example Usage:</b>
         * <pre>{@code
         *      String firstTag = (String) mx.call("get_pet")
         *                                   .returnJPath("$.tags[0].name");
         * }</pre>
         * 
         * @param jsonPath The JsonPath expression to evaluate (e.g., "$.id" or "$.items[0].name").
         * @return The extracted value (String, Number, Map, List, etc.), or {@code null} if no 
         *         response exists.
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
         * Extracts a value from the response body using a JsonPath and saves it 
         * into the global variables context.
         * * <p>This is useful for extracting IDs or tokens from one call to be used 
         * in subsequent calls via the {@code {{varName}}} syntax in YAML.</p>
         * 
         * <p> <b>Example Usage:</b></p>
         * <pre>{@code
         *      mx.call("create_pet")
         *        .saveVar("$.id", "newPetId") // Saved for later
         *        .call("get_pet");            // Uses {{newPetId}} in its YAML
         * }</pre>
         * 
         * @param jsonPath The path to the value in the response body.
         * @param varName  The name to store the value under in the global context.
         * @return This {@link MoxtureResult} for continued moxture chaining.
         */
        public MoxtureResult saveVar(String jsonPath, String varName) {
            Object value = returnJPath(jsonPath);
            moxter.vars().put(varName, value);
            return this;
        }

        /**
         * Convenience (and terminating) method to extract '$.id' from the response as a long.
         * 
         * <p> Useful for setup phases where only the ID is needed for subsequent logic.
         * 
         * <p> <b>Example Usage:</b>
         * <pre>{@code
         *      Long petId = mx.call("create_pet")
         *                     .returnId();
         *      // do stuff with the id
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
         * Performs fluent JSON path AssertJ assertions on the response body.
         * 
         * <p> This method extracts the value at the given {@code path} from the response body
         * and wraps it in an {@link AssertionPivot}. This pivot acts as a gateway, allowing you 
         * to perform standard object assertions or switch to specialized types (String, Boolean,
         * List) to access advanced AssertJ methods—all while maintaining the ability to chain 
         * multiple paths together using {@code .and()}.
         * 
         * <p> <b>Example Usage:</b>
         * <pre>{@code
         *    // 1. Direct object assertions:
         *    mx.call("get_pet")
         *      .assertBody("$.name").isEqualTo("Snowy");
         * 
        *     // (Get the actual name returned:)
         *    String actualName = mx.call("get_pet")
         *                          .assertBody("$.name").isEqualTo("Snowy")
         *                          .getActual();
         *
         *    // 2. Type-specific assertions with infinite chaining:
         *   mx.call("get_pet")
         *     .assertBody("$.species.name").isEqualTo("DOG")).and()   
         *          // isEquals being an Object-level check, works for any type
         *     .assertBody("$.name"        ).isEqualTo("Rex")).and()
         *     .assertBody("$.success"     ).asBoolean().isTrue()) 
         *          // we need to cast to a boolean to use that boolean-level check
         *     .assertBody("$.offspring[0]").asString().contains("child"));  
         *          // we need to cast to a String to use that String-level check
         *
         *   // 3. Native AssertJ chaining:
         *   mx.call("get_pet")
         *     .assertBody("$.name").asString().startsWith("Sno")
         *                                     .contains("owy")
         *                                     .isEqualTo("Snowy");
         * }</pre>
         * 
         * <p> Note: This method uses the {@code .as()} description in AssertJ. If an assertion
         * fails, the error message will automatically include context (e.g., 
         * "[Moxture 'get_pet' at path '$.name'] expected...").
         * 
         * @param path The JsonPath expression to extract from the response body.
         * @return An {@link AssertionPivot} providing both direct AssertJ methods and type-safe 
         *         bridges.
         * @throws RuntimeException if the path extraction fails.
         */
        public AssertionPivot assertBody(String path) {
            Object actual = returnJPath(path);
            String desc = String.format("Moxture '%s' at path '%s'", moxtureName, path);
            return new AssertionPivot(actual, desc);
        }


        /**
         * Performs a fluent assertion on a variable from the global scope.
         * 
         * <p> This is the preferred way to verify data. By using the variable name defined 
         * in the {@code save:} section of your YAML, you avoid hardcoding JsonPaths 
         * in your Java tests.
         * 
         * <p> <b>Example YAML:</b></p>
         * <pre>{@code
         *      save:
         *      petId: "$.id"
         *      petName: "$.name"
         * }</pre>
         * 
         * <p> <b>Example Usage:</b></p>
         * <pre>{@code
         *      // 1. Direct object assertions:
         *      mx.call("create_pet").assertVar("petName").isEqualTo("Snowy");
         * 
         *      // 2. Type-specific assertions with infinite chaining:
         *      mx.call("create_pet")
         *        .assertVar("petId").isEqualTo(123L).and()          // Object-level check
         *        .assertVar("petName").asString().contains("Snow").and() // Switch context
         *        .assertBody("$.status").isEqualTo("SUCCESS");      // Mix with body checks
         * 
         *      // 3. Native AssertJ chaining:
         *      mx.call("create_pet")
         *        .assertVar("petName").asString()
         *                             .isNotNull()
         *                             .startsWith("Sno")
         *                             .isEqualTo("Snowy");
         * }</pre>
         * 
         * @param varName The name of the variable from the Moxter global var context
         *                (e.g. defined in a moxture 'save' block).
         * @return An {@link AssertionPivot} providing both direct AssertJ methods and type-safe 
         *         bridges.
         * @see #assertBody(String) For assertions using raw JsonPaths.
         */
        public AssertionPivot assertVar(String varName) {
            Object actual = moxter.vars().get(varName);
            String description = String.format("Variable '%s' from moxture '%s'", varName, moxtureName);
            return new AssertionPivot(actual, description);
        }

        /**
         * A hybrid assertion "Pivot" that facilitates both general object assertions 
         * and fluent type-switching without encountering Java generic capture issues.
         * * <p>This class acts as a standalone wrapper around AssertJ's engine. It provides
         * direct access to common assertions (like {@code isEqualTo}) while offering 
         * "Bridge" methods (like {@code asString()}) to unlock type-specific 
         * functionality with a clean, infinite-chaining syntax.</p>
         * * <p><b>Why a Pivot?</b> By using a standalone pivot instead of extending 
         * {@link ObjectAssert} directly, we avoid naming collisions 
         * with AssertJ's internal methods and bypass the complex recursive generics 
         * that typically cause compiler "capture" errors in fluent APIs.</p>
         */
        public class AssertionPivot {
            private final Object val;
            private final String d;
            private final ObjectAssert<Object> internalAssert;

            protected AssertionPivot(Object value, String description) {
                this.val = value;
                this.d = description;
                this.internalAssert = Assertions.assertThat(value).as(description);  // NOSONAR the whole point is for the assertion to be resolved later
            }

            /**
             * Verifies that the actual value is equal to the given one.
             * @param expected the given value to compare the actual value to.
             * @return {@code this} pivot for continued object-level assertions.
             */
            public AssertionPivot isEqualTo(Object expected) { 
                internalAssert.isEqualTo(expected); 
                return this; 
            }

            /**
             * Verifies that the actual value is not {@code null}.
             * @return {@code this} pivot for continued object-level assertions.
             */
            public AssertionPivot isNotNull() { 
                internalAssert.isNotNull(); 
                return this; 
            }

            /**
             * Verifies that the actual value is {@code null}.
             * @return {@code this} pivot for continued object-level assertions.
             */
            public AssertionPivot isNull() { 
                internalAssert.isNull(); 
                return this; 
            }

            /**
             * Returns the {@link MoxtureResult} context to allow for chaining 
             * assertions on different JSON paths or variables.
             * * <p><b>Example:</b></p>
             * <pre>{@code
             * mx.call("pet")
             * .assertBody("$.id").isNotNull().and()
             * .assertBody("$.name").isEqualTo("Snowy");
             * }</pre>
             * * @return The original {@link MoxtureResult} caller.
             */
            public MoxtureResult and() { 
                return MoxtureResult.this; 
            }

            /**
             * Switches the assertion context to a {@link StringChain}.
             * This "unlocks" string-specific assertions like {@code contains()} or {@code startsWith()}.
             * * @return A {@link StringChain} bridge for fluent string assertions.
             */
            public StringChain asString() {
                var engine = (StringAssert) Assertions.assertThat(String.valueOf(val)).as(d);
                return new StringChain(engine);
            }

            /**
             * Switches the assertion context to a {@link BooleanChain}.
             * This "unlocks" boolean-specific assertions like {@code isTrue()} or {@code isFalse()}.
             * * @return A {@link BooleanChain} bridge for fluent boolean assertions.
             * @throws AssertionError if the underlying value is not a {@link Boolean}.
             */
            public BooleanChain asBoolean() {
                if (!(val instanceof Boolean)) {
                    throw new AssertionError(d + " is not a Boolean: " + val);
                }
                var engine = (BooleanAssert) Assertions.assertThat((Boolean) val).as(d);
                return new BooleanChain(engine);
            }

            /**
             * Switches the assertion context to a {@link ListChain}.
             * This "unlocks" list-specific assertions like {@code hasSize()} or {@code contains()}.
             * * @return A {@link ListChain} bridge for fluent list assertions.
             * @throws AssertionError if the underlying value is not a {@link java.util.List}.
             */
            @SuppressWarnings("unchecked")
            public ListChain asList() {
                if (!(val instanceof java.util.List)) {
                    throw new AssertionError(d + " is not a List");
                }
                var engine = (ListAssert<Object>) Assertions.assertThat((java.util.List<Object>) val).as(d);
                return new ListChain(engine);
            }

            /**
             * A fluent bridge for {@link StringAssert}.
             * Delegates to AssertJ while maintaining a path back to {@link MoxtureResult}.
             */
            public class StringChain {
                private final StringAssert engine;
                protected StringChain(StringAssert engine) { this.engine = engine; }

                public StringChain isEqualTo(String expected) { engine.isEqualTo(expected); return this; }
                public StringChain contains(String expected) { engine.contains(expected); return this; }
                public StringChain isBlank() { engine.isBlank(); return this; }
                public StringChain isNotBlank() { engine.isNotBlank(); return this; }
                
                /** Returns to the original result context to chain further path assertions. */
                public MoxtureResult and() { return MoxtureResult.this; }
            }

            /**
             * A fluent bridge for {@link BooleanAssert}.
             */
            public class BooleanChain {
                private final BooleanAssert engine;
                protected BooleanChain(BooleanAssert engine) { this.engine = engine; }

                public BooleanChain isTrue() { engine.isTrue(); return this; }
                public BooleanChain isFalse() { engine.isFalse(); return this; }
                public BooleanChain isEqualTo(boolean expected) { engine.isEqualTo(expected); return this; }

                /** Returns to the original result context to chain further path assertions. */
                public MoxtureResult and() { return MoxtureResult.this; }
            }

            /**
             * A fluent bridge for {@link ListAssert}.
             */
            public class ListChain {
                private final ListAssert<Object> engine;
                protected ListChain(ListAssert<Object> engine) { this.engine = engine; }

                public ListChain hasSize(int size) { engine.hasSize(size); return this; }
                public ListChain contains(Object... values) { engine.contains(values); return this; }
                public ListChain isEmpty() { engine.isEmpty(); return this; }

                /** Returns to the original result context to chain further path assertions. */
                public MoxtureResult and() { return MoxtureResult.this; }
            }
        }

    }


    // #############################################################################################


    /** 
     * Small utilities (logging helpers). 
     */
    static final class Utils 
    {
        public static class Yaml 
        {
            /**
             * Parses a YAML file into a {@link Model.MoxtureFile} while providing 
             * enhanced, developer-friendly error reporting on syntax failures.
             * 
             * <p>When Jackson (via SnakeYAML) encounters a parsing error (e.g., an unquoted 
             * template variable like {@code {{myVar}}} at the start of a YAML value), it typically 
             * throws a massive stack trace with a generic, hard-to-read message. 
             * 
             * <p>This wrapper intercepts that {@link com.fasterxml.jackson.core.JsonProcessingException}, 
             * extracts the exact line and column number, and throws a cleanly formatted 
             * 
             * {@link RuntimeException} that acts as a highly visible "Big Red Box" in the test console.
             *
             * @param mapper      The Jackson {@link ObjectMapper} configured with a YAML factory.
             * @param in          The input stream of the YAML file to parse.
             * @param displayPath The human-readable path of the file (e.g., "classpath:/moxtures.yaml") 
             * used to tell the developer exactly which file failed.
             * @return The fully parsed {@link Model.MoxtureFile} object graph.
             * @throws IOException If the input stream cannot be read from the file system or classpath.
             * @throws RuntimeException If a YAML syntax error occurs. The exception message contains 
             * the formatted, DX-friendly error block.
             */
            public static Model.MoxtureFile parseFile(ObjectMapper mapper, InputStream in, String displayPath) throws IOException {
                try {
                    return mapper.readValue(in, Model.MoxtureFile.class);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    com.fasterxml.jackson.core.JsonLocation loc = e.getLocation();
                    int line = loc != null ? loc.getLineNr() : -1;
                    int col = loc != null ? loc.getColumnNr() : -1;
                    String cleanMessage = e.getOriginalMessage();

                    String dxError = """
                        
                        =======================================================
                        MOXTER YAML SYNTAX ERROR 
                        =======================================================
                        File   : %s
                        Line   : %d
                        Column : %d
                        Details: %s
                        -------------------------------------------------------
                        Hint: If this is near a variable like {{var}}, make sure 
                        it is wrapped in double quotes if it's the first thing on the line!
                        =======================================================
                        """.formatted(displayPath, line, col, cleanMessage);
                    
                    // Throw as RuntimeException to stop execution immediately and print our custom message
                    throw new RuntimeException(dxError);
                }
            }
        }



        public static class Classpath {
            /**
             * Generates a list of paths by walking up from the startPath to the limitPath.
             * @param startPath The deepest directory to start from (e.g., "moxtures/com/fhi/app/MyTest").
             * @param limitPath The boundary to stop at (e.g., "moxtures").
             * @param fileName  The name of the file to append to each directory (e.g., "moxtures.yaml").
             * @return A list of full classpaths from closest to furthest.
             */
            public static List<String> walkUp(String startPath, String limitPath, String fileName) {
                List<String> paths = new ArrayList<>();
                String current = (startPath == null) ? "" : startPath.replaceAll("/$", "");
                String limit = (limitPath == null) ? "" : limitPath.replaceAll("/$", "");

                if (!current.isEmpty()) {
                    paths.add(current + "/" + fileName);
                }

                while (!current.equals(limit) && current.contains("/")) {
                    current = current.substring(0, current.lastIndexOf('/'));
                    paths.add(current + "/" + fileName);
                }

                String rootFile = limit.isEmpty() ? fileName : limit + "/" + fileName;
                if (!paths.contains(rootFile)) {
                    paths.add(rootFile);
                }
                return paths;
            }
        }


        /** 
         * Utilities for string-based variable substitution. 
         */
        public static class Interpolation 
        {
            /**
             * Performs a regex-based substitution of variables within a template string.
             *
             * <p>Matches the {@code {{variableName}}} syntax and replaces it with the 
             * string representation of the value found in the provided map. 
             * 
             * <p>Features:
    
             * - <b>Type Safety:</b> Uses {@code String.valueOf(v)} to handle numbers and booleans.
             * - <b>Null Safety:</b> Replaces null values with the literal string "null".
             * - <b>Regex Safety:</b> Uses {@code Matcher.quoteReplacement} so that values 
             * containing special characters (like '$' or '\') don't break the interpolation.
             * - <b>Graceful Failure:</b> If a variable is missing, the original placeholder 
             * {@code {{key}}} is preserved in the output to help with debugging.

             *
             * @param template  The string containing {@code {{key}}} placeholders.
             * @param variables The map of available variable keys and values.
             * @return The interpolated string.
             */
            public static String interpolate(String template, Map<String, Object> variables) {
                if (template == null || !template.contains("{{")) {
                    return template;
                }

                // Pattern matches {{ followed by any non-bracket chars, ending in }}
                Pattern pattern = Pattern.compile("\\{\\{([^}]+)\\}\\}");
                Matcher matcher = pattern.matcher(template);
                StringBuilder sb = new StringBuilder();

                while (matcher.find()) {
                    String key = matcher.group(1).trim(); 

                    if (variables != null && variables.containsKey(key)) {
                        Object value = variables.get(key);
                        String replacement = (value == null) ? "null" : String.valueOf(value);
                        
                        // quoteReplacement is vital to prevent $1 or \ appearing in 
                        // data from being interpreted as regex groups
                        matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                    } else {
                        // Keep the original {{key}} if missing so the user can see the error
                        log.warn("[Moxter] Interpolation warning: Variable '{{{}}}' missing from context.", key);
                        matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
                    }
                }
                matcher.appendTail(sb);
                return sb.toString();
            }
        }




        public static class IO 
        {
            /**
             * Locates resources using a primary loader (TCCL) and an optional 
             * secondary classloader.
             * 
             * Locates a resource on the classpath using a tiered search strategy.
             * 1. Thread Context ClassLoader (TCCL) - Good for Spring/JUnit environments.
             * 2. Anchor ClassLoader (Test Class) - Finds resources local to the test.
             * 3. Utility ClassLoader (Fallback) - Finds global resources in the engine.
             * 
             * @param path The resource path.
             * @param anchor If provided, use this class's loader as a secondary search point.
             * @return The URL of the resource, or null if not found.
             */
            public static URL findResource(String path, Class<?> anchor) {
                // 1. Try the current thread's context classloader (standard for web/Spring apps)
                ClassLoader tccl = Thread.currentThread().getContextClassLoader();
                URL url = (tccl != null) ? tccl.getResource(path) : null;
                if (url != null) return url;

                // 2. Fallback to the anchor class provided (usually the test class)
                if (anchor != null) {
                    url = anchor.getClassLoader().getResource(path);
                    if (url != null) return url;
                }

                // 3. Last resort: use the classloader that loaded this Utility class itself
                return IO.class.getClassLoader().getResource(path);
            }


            private static String parentDirOf(String path) {
                int i = path.lastIndexOf('/');
                return (i > 0) ? path.substring(0, i) : "";
            }


            /**
             * Utility to read a classpath resource as a byte array. 
             * 
             * Used for binary bodys like images or PDFs.
             * 
             * @param baseDir The base directory for relative paths.
             * @param rawPath The path (relative or absolute starting with /).
             * @return The raw bytes of the resource.
             */
            public static byte[] readResourceBytes(String baseDir, String rawPath) 
            {
                String path = rawPath.startsWith("/") ? rawPath.substring(1) : (baseDir + "/" + rawPath);
                URL url = findResource(path);

                if (url == null) throw new IllegalArgumentException("Resource not found: " + rawPath);

                try (InputStream in = url.openStream()) {
                    return in.readAllBytes();
                } catch (IOException e) {
                    throw new RuntimeException("Failed reading bytes from " + path, e);
                }
            }

            /**
             * Helper to locate resources using the Thread Context ClassLoader or Moxter ClassLoader.
             */
            public static URL findResource(String path) {
                URL url = null;
                ClassLoader tccl = Thread.currentThread().getContextClassLoader();
                if (tccl != null) url = tccl.getResource(path);
                if (url == null) {
                    ClassLoader fallback = Moxter.class.getClassLoader();
                    if (fallback != null) url = fallback.getResource(path);
                }
                return url;
            }

        }

        public static class Http 
        {
            /**
             * Determines if a Content-Type header indicates a JSON body.
             * 
             * <p>This method is intentionally broad. It safely handles nulls, ignores case, 
             * ignores additional parameters (like {@code charset=utf-8}), and specifically 
             * supports vendor-specific JSON extensions (like {@code application/problem+json} 
             * or {@code application/vnd.api+json}).
             *
             * @param ctHeader The raw Content-Type header string (can be null).
             * @return {@code true} if the header implies JSON content, {@code false} otherwise.
             */
            public static boolean isJsonContentType(String ctHeader) {
                if (ctHeader == null) return false;
                String ct = ctHeader.toLowerCase(Locale.ROOT);
                return ct.contains("application/json") || ct.contains("+json");
            }

            /**
             * Checks if an HTTP method typically requires a CSRF token.
             * 
             * <p>In Spring Security, state-changing methods require CSRF protection by default. 
             * Moxter uses this to automatically inject a valid CSRF token into the MockMvc 
             * request so developers don't have to manually mock CSRF handshakes in their YAML.
             *
             * @param method The HTTP method (e.g., "POST", "GET").
             * @return {@code true} if the method is POST, PUT, PATCH, or DELETE.
             */
            public static boolean requiresCsrf(String method) {
                if (method == null) return false;
                String m = method.toUpperCase(Locale.ROOT);
                return m.equals("POST") || m.equals("PUT") || m.equals("PATCH") || m.equals("DELETE");
            }

            /**
             * Translates a string-based HTTP method into a Spring {@link MockHttpServletRequestBuilder}.
             * 
             * <p>Acts as the core factory for the HTTP execution pipeline, mapping the declarative 
             * YAML method string into the actual Spring testing component.
             *
             * @param method The HTTP method (defaults to "GET" if null).
             * @param uri    The fully resolved target URI.
             * @return A builder initialized for the specified HTTP method.
             * @throws IllegalArgumentException if the HTTP method is unsupported.
             */
            public static MockHttpServletRequestBuilder toRequestBuilder(String method, URI uri) {
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

            /**
             * Appends a map of query parameters to a given endpoint URL.
             * 
             * <p>It correctly handles URLs that already contain query parameters (appending with 
             * {@code &} instead of {@code ?}) and ensures all keys and values are URL-encoded.
             *
             * @param endpoint The base URL (e.g., "/api/pets" or "/api/pets?sort=asc").
             * @param query    A map of query parameters to append.
             * @return The fully constructed URI string.
             */
            public static String appendQuery(String endpoint, Map<String,String> query) {
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

            /**
             * URL-encodes a string using UTF-8.
             * 
             * <p>Wraps {@link URLEncoder#encode(String, String)} to hide the checked 
             * {@code UnsupportedEncodingException}, as UTF-8 is guaranteed to be available 
             * on all modern JVMs.
             *
             * @param s The string to encode.
             * @return The URL-encoded string.
             */
            public static String urlEncode(String s) {
                try { return URLEncoder.encode(s, StandardCharsets.UTF_8.toString()); }
                catch (Exception e) { throw new RuntimeException(e); }
            }

            /**
             * Extracts all headers from a Spring MockMvc response into a standard Map.
             * 
             * <p>This isolates Moxter's internal {@code ResponseEnvelope} from Spring-specific 
             * classes, making the response data agnostic and easier to assert against.
             *
             * @param r The Spring MockHttpServletResponse.
             * @return A map of header names to lists of header values.
             */
            public static Map<String, List<String>> copyHeaders(MockHttpServletResponse r) {
                Map<String, List<String>> h = new LinkedHashMap<>();
                for (String name : r.getHeaderNames()) h.put(name, new ArrayList<>(r.getHeaders(name)));
                return h;
            }

            /**
             * Ensures an HTTP method string is never null.
             *
             * @param method The method string to check.
             * @return The original method, or "GET" if it was null.
             */
            public static String safeMethod(String method) { 
                return method == null ? "GET" : method; 
            }
        }


        public static class Misc
        {
            /**
             * Safely extracts the principal's name from a Spring Security Authentication object.
             * 
             * <p>This is strictly used for logging. It swallows any potential exceptions 
             * (like uninitialized proxy objects or custom auth implementations throwing 
             * unexpected errors) to ensure that a simple debug log statement never crashes 
             * an otherwise successful test execution.
             *
             * @param a The Spring Security Authentication object (can be null).
             * @return The principal's name, or "(unknown)" if extraction fails.
             */
            public static String safeName(Authentication a) {
                try { return a.getName(); } catch (Exception ignore) { return "(unknown)"; }
            }

            /**
             * Extracts the most meaningful error message from a Throwable.
             * 
             * <p>Frameworks like Spring or Jackson often wrap the true cause of an error in 
             * generic wrapper exceptions with blank messages. This method digs down one level 
             * to the cause if the top-level message is missing, ensuring the console logs 
             * actually tell the developer what went wrong.
             *
             * @param t The exception thrown during execution.
             * @return A non-blank string representing the best available error message.
             */
            public static String rootMessage(Throwable t) {
                if (t == null) return "(no message)";
                String m = t.getMessage();
                if (m != null && !m.isBlank()) return m;
                Throwable c = t.getCause();
                if (c != null && c.getMessage() != null && !c.getMessage().isBlank()) return c.getMessage();
                return t.toString();
            }

            /**
             * Returns the first string that is neither null nor completely blank.
             * 
             * <p>Used heavily during the `basedOn` materialization phase to determine if a 
             * child moxture has overridden a scalar value from its parent (e.g., overriding 
             * the HTTP method or endpoint).
             *
             * @param a The primary string to check (usually the child's value).
             * @param b The fallback string (usually the parent's value).
             * @return The first valid string, or {@code b} if {@code a} is blank.
             */
            public static String firstNonBlank(String a, String b) {
                return (a != null && !a.isBlank()) ? a : b;
            }

            /**
             * Performs a shallow merge of two maps, with the child map taking precedence.
             * 
             * <p>Used during the `basedOn` materialization phase to merge HTTP headers, 
             * query parameters, and variable scopes. It guarantees that if a child defines 
             * the same key as a parent, the child's value completely overwrites the parent's.
             * 
             * <p>Note: This method intentionally returns a {@link LinkedHashMap} to preserve 
             * the exact insertion order defined by the user in the YAML file.
             *
             * @param parent The base map from the parent moxture.
             * @param child  The overriding map from the child moxture.
             * @param <K>    The type of keys maintained by this map.
             * @param <V>    The type of mapped values.
             * @return A new, order-preserving map containing the merged result.
             */
            public static <K, V> Map<K, V> mergeMap(Map<K, V> parent, Map<K, V> child) {
                if ((parent == null || parent.isEmpty()) && (child == null || child.isEmpty())) {
                    return child;
                }
                Map<K, V> out = new LinkedHashMap<>();
                if (parent != null) out.putAll(parent);
                if (child != null) out.putAll(child); // Child overwrites parent keys here
                return out;
            }
        }


        public static class Json 
        {
            /**
             * Performs a fast, heuristic check to determine if a string loosely resembles a JSON body.
             *
             * <p>This method strips leading and trailing whitespace and checks if the string 
             * is properly enclosed in object ('{}') or array ('[]') delimiters. This acts as a cheap 
             * "sniff test" to prevent handing plain text or HTML to Jackson, avoiding expensive 
             * and unnecessary parsing exceptions.
             *
             * @param text The raw string content to inspect.
             * @return {@code true} if the non-blank string starts and ends with matching JSON delimiters, 
             * {@code false} otherwise.
             */
            public static boolean looksLikeJson(String text) {
                if (text == null || text.isBlank()) return false;
                
                String s = text.strip();
                char first = s.charAt(0);
                char last = s.charAt(s.length() - 1);
                
                return (first == '{' && last == '}') || (first == '[' && last == ']');
            }

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

            /** 
             * Robustly converts textual JSON (including block scalars) into a JsonNode 
             * so it can participate in deep-merging operations.
             * Otherwise return as is. 
             */
            public static JsonNode coerceJsonTextToNode(ObjectMapper mapper, JsonNode n) {
                if (n == null || !n.isTextual()) return n;
                
                String s = n.asText().trim();
                // Check if it's a "sniffed" JSON string or a classpath reference
                if (looksLikeJson(s)) {
                    try { return mapper.readTree(s); } catch (Exception ignore) { return n; }
                }
                return n;
            }
            private static JsonNode coerceJsonTextToNodeOLD(ObjectMapper mapper, JsonNode n) {
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

        }


        static final class Logging 
        {
            /**
             * Safely truncates a string to a maximum length to prevent log bloat.
             * 
             * <p>If the string exceeds the maximum length, it cuts the string and appends 
             * a helpful suffix indicating exactly how many characters were omitted.
             *
             * @param s   The string to truncate (can be null).
             * @param max The maximum allowed length before truncation kicks in.
             * @return The truncated string, or the original if it was within limits.
             */
            static String truncate(String s, int max) {
                if (s == null) return null;
                if (s.length() <= max) return s;
                return s.substring(0, max) + " ...(" + (s.length() - max) + " more chars)";
            }

            /**
             * Creates a sanitized preview of the variable context for debug logging.
             * 
             * <p>This method prevents credential leakage in CI/CD logs by actively looking for 
             * keys containing the word "token" (case-insensitive) and masking their values.
             *
             * @param vars The current map of scoped variables.
             * @return A sanitized, shallow copy of the variables map.
             */
            static Map<String,Object> previewVars(Map<String,Object> vars) {
                Map<String,Object> out = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : vars.entrySet()) {
                    String k = e.getKey();
                    Object v = e.getValue();
                    out.put(k, k.toLowerCase(Locale.ROOT).contains("token") ? "***" : v);
                }
                return out;
            }

            /**
             * Creates a sanitized preview of HTTP request headers for debug logging.
             * 
             * <p>Specifically targets and masks the {@code Authorization} header so Bearer 
             * tokens or Basic Auth credentials are not printed in plain text.
             *
             * @param headers The HTTP request headers.
             * @return A string representation of the sanitized headers.
             */
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

            /** 
             * Flattens and sanitizes HTTP response headers for debug logging.
             * 
             * <p>Since MockMvc returns headers as a {@code Map<String, List<String>>}, 
             * this method joins multiple values with a comma to keep the log output 
             * compact, while still masking the {@code Authorization} header.
             * 
             * @param headers The HTTP response headers.
             * @return A string representation of the flattened, sanitized headers.
             */
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

            /**
             * Attempts to pretty-print a Jackson JSON tree for debug output.
             * 
             * <p>Logging should never crash an application. If pretty-printing fails 
             * for any reason (e.g., circular references or custom serializers), this 
             * catches the exception and falls back to a standard {@code toString()}.
             *
             * @param n The JsonNode to format.
             * @return A safely formatted JSON string, or "(none)" if null.
             */
            static String previewNode(JsonNode n) {
                if (n == null) return "(none)";
                try { return n.toPrettyString(); } catch (Exception e) { return n.toString(); }
            }

            /**
             * Generates a safe, truncated string representation of an arbitrary object.
             * 
             * <p>Used primarily when dumping variables of unknown types, ensuring that 
             * a massive object structure doesn't accidentally dump thousands of lines 
             * into the console. Caps the output at 200 characters.
             *
             * @param v The object to preview.
             * @return A truncated string representation.
             */
            static String previewValue(Object v) {
                if (v == null) return "null";
                String s = String.valueOf(v);
                return s.length() > 200 ? s.substring(0, 200) + " …" : s;
            }
        }
    }



    // #############################################################################################

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
            private String protocol; // NEW: e.g., "http", "stomp"
            private String name;
            private RootOptionsDef options;
            private String method;
            private String endpoint;
            private Map<String,String> headers;
            private Map<String,String> query;
            private JsonNode body;           // Replaces 'payload'
                                             // YAML object/array OR text ("classpath:..." or raw JSON string)
            private Map<String,String> save; // varName -> JSONPath

            @JsonAlias({"extends"})
            private String basedOn;

            // group-as-moxture: if present, indicates this row is a group
            private List<String> moxtures;
            private List<MultipartDef> multipart;
            private Map<String, Object> vars;
            private ExpectDef expect;

            /** 
             * Holds the hierarchy of bodies: [Parent, Child].
             * This allows deep-merging to be deferred until runtime when 
             * variables are actually available.
             */
            private List<JsonNode> bodyStack = new ArrayList<>();

            /**
             * Determines if this moxture is defined as a STOMP protocol call.
             */
            public boolean isProtocolStomp() {
                return "stomp".equalsIgnoreCase(this.protocol);
            }
        }

        @Getter @Setter
        public static final class RootOptionsDef {
            
            private Boolean allowFailure = null; // not boolean because that silently defaults to false
            private Boolean verbose = null;      // and in a child basedOn parent moxture situation
                                                 // then the defaults of the child would override
                                                 // whatever comes from the parent.
        }

        @Getter @Setter
        public static class ExpectDef 
        {
            private JsonNode status;            // int | "2xx"/"3xx"/"4xx"/"5xx" | "201" | [ ... any of those ... ]
            private ExpectBodyDef body;
            private ExpectBroadcastDef broadcast;
        }

        @Getter @Setter
        public static class ExpectBodyDef 
        {
            private ExpectBodyMatchDef match;
            @JsonProperty("assert")  // assert is reserved keyword => can't use it verbatim as a Java attribute
            private ExpectBodyAssertDef assertDef;  
            // TODO later
            //private AssertSchema schema;
        }

        @Getter @Setter
        public static class ExpectBroadcastDef 
        {
            private String topic;
            private String type; // e.g., "byte[]", "json", "String"
        }

        @Getter @Setter
        public static class ExpectBodyMatchDef 
        {
            private String mode; // "full" or "partial"
            private List<String> ignorePaths;
            private JsonNode content;
        }

        @Getter @Setter
        @NoArgsConstructor
        public static class ExpectBodyAssertDef 
        {
            // This map holds all the "$.path": "value" pairs
            private Map<String, JsonNode> paths = new LinkedHashMap<>();

            // Jackson will automatically assume any unrecognized property 
            // under the 'assert' block is a "$.path" to assert, and store it in here:
            @JsonAnySetter
            public void addAssertPath(String path, JsonNode expectedValue) {
                this.paths.put(path, expectedValue);
            }
            
            public boolean isEmpty() {
                return paths.isEmpty();
            }
        }
    }





}
