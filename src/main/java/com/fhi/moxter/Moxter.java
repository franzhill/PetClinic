package com.fhi.moxter;

import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;

import java.io.IOException;
import java.io.InputStream;
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
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

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


    private final IMoxtureLoader loader; 

    // Used if loader = hierarchical package loader
    private Class<?> testClass;
    //#private MoxtureLoaderDELETE.ClasspathMoxtureRepository repo;
    //#private MoxtureLoaderDELETE.MoxtureLoadingConfig cfg;


    private final Model.MoxtureFile suite;
    private final Map<String, Model.Moxture> byName;

    // Concurrent allows tests to be run in parallel
    //#private final ConcurrentMap<String, Object> vars = new ConcurrentHashMap<>();
    private final Map<String,Object> vars = new LinkedHashMap<>();

    private final Runtime.HttpExecutor executor;
    private final String moxturesBaseDir;


    private final ObjectMapper yamlMapper;
    // Keep JSON mapper for HTTP
    private final ObjectMapper jsonMapper; // NOSONAR yes it's used!


    // Auth supplier (fixed or lazy) from builder
    private final java.util.function.Supplier<org.springframework.security.core.Authentication> builderAuthSupplier;

    // Caches and Resolvers
    private final Map<MoxtureResolver.EffectiveKey, Model.Moxture> materializedCache = new LinkedHashMap<>();
    private final MoxVars globalScopeVars = new MoxVars(this, null);
    private final MoxtureResolver moxResolver = new MoxtureResolver(this);


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
    public MoxtureCaller caller() {
        return new MoxtureCaller(this);
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

    private Moxter(IMoxtureLoader loader,
                   MockMvc mockMvc,
                   java.util.function.Supplier<org.springframework.security.core.Authentication> authSupplier,
                   ObjectMapper yamlMapper,
                   ObjectMapper jsonMapper)
    {
        this.loader = Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(mockMvc, "mockMvc");
        this.builderAuthSupplier = authSupplier;
        this.yamlMapper = yamlMapper;
        this.jsonMapper = jsonMapper;

        // 1. Initial Load via Strategy
        IMoxtureLoader.LoadedSuite loaded = loader.loadInitial();
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
            MoxtureResolver.validateMoxture(f);
            if (index.put(f.getName(), f) != null) {
                throw new IllegalStateException("Duplicate moxture name: " + f.getName());
            }
        }
        this.byName = Collections.unmodifiableMap(index);

        // 4. Runtime helpers & wiring
        Runtime.StatusMatcher matcher = new Runtime.StatusMatcher();
        Runtime.Templating templating = new Runtime.SimpleTemplating();
        Runtime.PayloadResolver payloadResolver = new Runtime.PayloadResolver(yamlMapper);

        this.executor = new Runtime.HttpExecutor(mockMvc, jsonMapper, templating, payloadResolver, matcher, builderAuthSupplier);
    }

    // =====================================================================
    //   Private helpers
    // =====================================================================

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
        private MockMvc mockMvc;
        private java.util.function.Supplier<org.springframework.security.core.Authentication> authSupplier;

        private MoxBuilder(Class<?> testClass) { 
            this.testClass = testClass; 
        }

        /**
         * Provides the Spring {@link MockMvc} instance that Moxter will use to execute 
         * all HTTP requests defined in the YAML moxtures.
         * 
         * @param mvc The configured MockMvc instance (mandatory).
         * @return this {@link MoxBuilder} for chaining.
         */
        public MoxBuilder mockMvc(MockMvc mvc) { 
            this.mockMvc = mvc; 
            return this; 
        }

        /**
         * Provides a fixed Spring Security {@link org.springframework.security.core.Authentication} 
         * object to automatically attach to every HTTP request executed by this engine instance.
         *
         * <p>Use this if your entire test suite runs under a single simulated user.
         * 
         * @param auth The Authentication object to inject.
         * @return this {@link MoxBuilder} for chaining.
         */
        public MoxBuilder authentication(org.springframework.security.core.Authentication auth) {
            this.authSupplier = () -> auth;
            return this;
        }

        /** 
         * Provides a dynamic supplier for Spring Security {@link org.springframework.security.core.Authentication}.
         * 
         * <p>The supplier is evaluated <i>per-request</i>. Use this if your test context 
         * changes the active user dynamically during execution.
         * 
         * @param s The Authentication supplier.
         * @return this {@link MoxBuilder} for chaining.
         */
        public MoxBuilder authenticationSupplier(java.util.function.Supplier<org.springframework.security.core.Authentication> s) {
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
            ObjectMapper yamlMapper = defaultYamlMapper();
            ObjectMapper jsonMapper = defaultJsonMapper();
            
            // Wire up the legacy JUnit hierarchical config
            HierarchicalMoxtureLoader.MoxtureLoadingConfig cfg = new HierarchicalMoxtureLoader.MoxtureLoadingConfig(
                    DEFAULT_MOXTURES_ROOT_PATH,
                    DEFAULT_USE_PER_TESTCLASS_DIRECTORY,
                    DEFAULT_MOXTURES_BASENAME
            );
            
            IMoxtureLoader loader = new HierarchicalMoxtureLoader(testClass, cfg, yamlMapper);
            
            return new Moxter(loader, mockMvc, authSupplier, yamlMapper, jsonMapper);
        }
    }

    // ############################################################################


    /**
     * Strategy interface for discovering and loading Moxture definitions.
     * 
     * <p>By abstracting the discovery logic, Moxter can support different loading 
     * paradigms—such as JUnit-style hierarchical classpath scanning or standalone 
     * explicit file imports—without modifying the core execution engine.
     */
    public interface IMoxtureLoader {

        /**
         * Performs the initial load of the primary moxture file (the entry point).
         * 
         * <p>For a hierarchical strategy, this is the file closest to the test class. 
         * For an explicit strategy, this is the file specifically requested by the user.</p>
         * 
         * @return A {@link LoadedSuite} containing the parsed entry-point file and its anchor directory.
         * @throws RuntimeException if the entry-point file cannot be found or parsed.
         */
        LoadedSuite loadInitial();

        /**
         * Resolves the initial variables map used to seed the engine's global context.
         * * <p>Implementations determine how these variables are gathered. A hierarchical 
         * loader might walk up the package tree to merge variables, while an explicit 
         * loader might only read the current file and its explicit includes.</p>
         * * @return A map of variables discovered through the loading strategy, or an empty map if none exist.
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


    // ############################################################################

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
    public static class HierarchicalMoxtureLoader implements IMoxtureLoader {

        private final Class<?> testClass;
        private final MoxtureLoadingConfig cfg;
        private final ObjectMapper yamlMapper;
        private final ClasspathMoxtureRepository repo;

        public HierarchicalMoxtureLoader(Class<?> testClass, MoxtureLoadingConfig cfg, ObjectMapper yamlMapper) {
            this.testClass = Objects.requireNonNull(testClass, "testClass");
            this.cfg = Objects.requireNonNull(cfg, "cfg");
            this.yamlMapper = Objects.requireNonNull(yamlMapper, "yamlMapper");
            this.repo = new ClasspathMoxtureRepository(yamlMapper);
        }

        @Override
        public LoadedSuite loadInitial() {
            ClasspathMoxtureRepository.RepoLoadedSuite res = repo.loadFor(testClass, cfg);
            return new LoadedSuite(res.suite(), res.baseDir());
        }

        @Override
        public RawMoxture findByName(String name, String currentBaseDir) {
            ClasspathMoxtureRepository.RepoRawMoxture res = repo.findFirstByNameFromBaseDir(testClass, cfg, currentBaseDir, name, yamlMapper);
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
        public static final class MoxtureLoadingConfig {
            final String rootPath;                // e.g., "integrationtests2/moxtures"
            final boolean perTestClassDirectory;  // true => add "/{TestClassName}"
            final String fileName;                // "moxtures.yaml" (includes extension)

            public MoxtureLoadingConfig(String rootPath, boolean perTestClassDirectory, String fileName) {
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
        static final class ClasspathMoxtureRepository {
            private final ObjectMapper mapper;

            ClasspathMoxtureRepository(ObjectMapper mapper) { this.mapper = mapper; }

            record RepoLoadedSuite(Model.MoxtureFile suite, String baseDir) {}
            record RepoRawMoxture(Model.Moxture call, String baseDir, String displayPath) {}

            public RepoLoadedSuite loadFor(Class<?> testClass, MoxtureLoadingConfig cfg) {
                final String classpath = buildClosestClasspath(testClass, cfg);
                final String displayPath = "classpath:/" + classpath;

                URL url = Utils.IO.findResource(classpath, testClass);

                if (url == null) {
                    throw new IllegalStateException(
                        "[Moxter] No moxtures file found for " + testClass.getName() + "\n" +
                        "Expected at: " + displayPath + "\n" +
                        "Hint: place the file under src/test/resources/" + classpath
                    );
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
            List<String> candidateAncestorPaths(Class<?> testClass, MoxtureLoadingConfig cfg) {
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
            RepoRawMoxture findFirstByNameFromBaseDir(Class<?> testClass, MoxtureLoadingConfig cfg,
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


            private static String buildClosestClasspath(Class<?> testClass, MoxtureLoadingConfig cfg) {
                String pkg = (testClass.getPackageName() == null ? "" : testClass.getPackageName().replace('.', '/'));
                String classDir = cfg.perTestClassDirectory ? ("/" + testClass.getSimpleName()) : "";
                String base = (pkg.isEmpty() ? cfg.rootPath : (cfg.rootPath + "/" + pkg)) + classDir + "/";
                String full = base + cfg.fileName;
                log.debug("[Moxter] Expecting moxtures at: classpath:/{} for {}", full, testClass.getName());
                return full;
            }
        }
    }






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
     * <ul>
     *   <li><b>Lookup:</b> Performs a "closest-first" search for moxtures by name, 
     *        starting from the test class directory and walking up through parent packages 
     *        to the root moxtures directory.</li>
     *   <li><b>Deep Materialization:</b> Recursively resolves inheritance chains (via {@code basedOn} 
     *        or {@code extends}). It merges parents and children using specific precedence rules:
     *     <ul>
     *       <li>Scalars (method, endpoint) are overridden by the child.</li>
     *       <li>Maps (headers, query, vars) are shallow-merged (child wins).</li>
     *       <li>Lists (save, moxtures) are replaced entirely by the child.</li>
     *     </ul>
     *    </li>
     *    <li><b>Cycle Detection:</b> Prevents infinite recursion in inheritance chains by 
     *        maintaining a visiting stack and throwing an {@link IllegalStateException} 
     *        if a cycle is detected.</li>
     *    <li><b>Payload Stacking:</b> Instead of merging JSON bodies during resolution, 
     *        it builds a {@code bodyStack} to preserve the hierarchy. This allows the 
     *        {@code PayloadResolver} to handle variable interpolation and deep-merging 
     *        correctly at runtime.</li>
     * </ul>
     * 
     * <p><b>Caching:</b> Resolved moxtures are cached in the {@code materializedCache} 
     * to ensure performance and consistency throughout the test suite execution.
     */
    public static final class MoxtureResolver
    {
        // Reference to the root encompassing engine
        private final Moxter moxter;

        protected MoxtureResolver(Moxter moxter) {
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
         * a payload or multipart file is defined as {@code "classpath:request.json"}).
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
            IMoxtureLoader.RawMoxture found = moxter.loader.findByName(name, moxter.moxturesBaseDir);
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
         *  - payload: deep-merge objects; arrays/scalars replace
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
            IMoxtureLoader.RawMoxture parentResolved = moxter.loader.findByName(parentName, nodeBaseDir);

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

            // PAYLOAD HANDLING: Build the stack instead of merging nodes
            List<JsonNode> combinedStack = new ArrayList<>(materializedParent.getBodyStack());
            if (node.getBody() != null) {
                combinedStack.add(node.getBody());
            }
            merged.setBodyStack(combinedStack);
            
            // Keep the 'body' field pointing to the latest definition for backward compatibility
            merged.setBody(node.getBody() != null ? node.getBody() : materializedParent.getBody());
/* OLD
            Map<String, Object> allVars = Utils.Misc.mergeMap(moxter.vars().view(), node.getVars());
            JsonNode payload = deepMergePayload(moxter.yamlMapper, materializedParent.getBody(), node.getBody(), allVars);
            merged.setBody(payload);
 */
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
            c.setMethod(src.getMethod());
            c.setEndpoint(src.getEndpoint());
            c.setHeaders(src.getHeaders()==null?null:new LinkedHashMap<>(src.getHeaders()));
            c.setVars(src.getVars() == null ? null : new LinkedHashMap<>(src.getVars()));
            c.setQuery(src.getQuery()==null?null:new LinkedHashMap<>(src.getQuery()));
            c.setSave(src.getSave()==null?null:new LinkedHashMap<>(src.getSave()));
            c.setExpect(src.getExpect());
            if (src.getOptions() != null) {
                Model.RootOptionsDef opt = new Model.RootOptionsDef();
                opt.setAllowFailure(src.getOptions().isAllowFailure());
                opt.setVerbose(src.getOptions().isVerbose());
                c.setOptions(opt);
            }
            c.setMoxtures(src.getMoxtures()==null?null:new ArrayList<>(src.getMoxtures()));
            c.setMultipart(src.getMultipart() == null ? null : new ArrayList<>(src.getMultipart()));
            c.setBasedOn(null);
            c.setBody(src.getBody()); // JSON nodes are fine to share for our usage

            // ---> THE FIX: Preserve the body stack if it was already built! <---
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
        private static JsonNode deepMergePayload(ObjectMapper mapper, JsonNode parent, JsonNode child) {
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
                        merged.set(key, deepMergePayload(mapper, parentVal, childVal));
                    } else {
                        merged.set(key, childVal.deepCopy());
                    }
                }
                return merged;
            }
            // 3. Fallback: Child replaces Parent (Arrays/Scalars)
            return child.deepCopy();
        }

        /** Helper to turn a templated Block Scalar into a mergeable JsonNode */
        private static JsonNode coerceAndInterpolate(ObjectMapper mapper, JsonNode n, Map<String, Object> vars) {
            if (n == null || !n.isTextual()) return n;
            String raw = n.asText();
            // Use the utility to replace {{vars}} before parsing
            String interpolated = Utils.Interpolation.interpolate(raw, vars);
            if (Utils.Json.looksLikeJson(interpolated)) {
                try { return mapper.readTree(interpolated); } catch (Exception ignore) { }
            }
            return n;
        }


        private static JsonNode deepMergePayloadOLC(ObjectMapper mapper, JsonNode parent, JsonNode child) {
            parent = Utils.Json.coerceJsonTextToNode(mapper, parent);
            child  = Utils.Json.coerceJsonTextToNode(mapper, child);

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
                        merged.set(k, deepMergePayloadOLC(mapper, parentVal, childVal)); // recursive objects
                    } else {
                        merged.set(k, childVal); // replace arrays/scalars or add new fields
                    }
                });
                return merged;
            }

            // Types differ or parent not object → replace
            return child;
        }



    }






    // ############################################################################

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
    public static class MoxtureCaller 
    {
        // Reference to the root encompassing engine
        private final Moxter moxter;


        private Map<String, Object> overrides = new LinkedHashMap<>();

        private boolean allowFailure = false;
        private boolean verbose = false; // replacing printReturn
        private boolean jsonPathLax = false;


        /**
         * Toggles 'allowFailure' mode for this specific call.
         *
         * When enabled, exceptions and assertion errors are caught and logged
         * as warnings yet will not fail the moxture.
         * 
         * Lets us have "best effort" moxtures the good completion of which are not
         * strictly mandatory.
         */
        public MoxtureCaller allowFailure(boolean allowFailure) {
            this.allowFailure = allowFailure;
            return this;
        }

        /**
         * Increases the console feedback (disregarding logger threshold)
         */
        public MoxtureCaller verbose(boolean verbose) {
            this.verbose = verbose;
            return this;
        }


        /**
         * For end-users: use Moxter.caller() instead
         * 
         * @param moxter      The encompassing Engine.
         */
        protected MoxtureCaller(Moxter moxter) {
            this.moxter = moxter;
        }


        /**
         * Toggles 'Lax' mode for JsonPath extractions.
         * 
         * If true, failed JsonPath extractions will return null rather than throwing an exception.
         * 
         * @param val true to enable lax JsonPath evaluation.
         * @return this {@link MoxtureCaller} for chaining.
         */
        public MoxtureCaller withJsonPathLax(boolean val) {
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
         * @return this {@link MoxtureCaller} for chaining.
         */
        public MoxtureCaller with(Map<String, Object> overrides) {
            if (overrides != null) this.overrides.putAll(overrides);
            return this;
        }

        /**
         * Injects a single variable into the moxture call context.
         * 
         * @param key   The variable name (used as ${key} in YAML).
         * @param value The value to inject.
         * @return this {@link MoxtureCaller} for chaining.
         */
        public MoxtureCaller with(String key, Object value) {
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
            Runtime.ResponseEnvelope env = callInternal(moxtureName, overrides);

            return new MoxtureResult(env, moxter, moxtureName);
        }


        /**
         * TODO 
         * @param moxtureName
         * @param overrides
         * @return
         */
        private Runtime.ResponseEnvelope callInternal(String moxtureName, Map<String, Object> overrides) 
        {
            Objects.requireNonNull(moxtureName, "name");
            MoxtureResolver.EffectiveMoxture r = this.moxter.moxResolver.resolveByName(moxtureName);

            // ---> THE BLENDER <---
            // Merge the YAML spec with the runtime Java overrides
            Model.Moxture finalSpec = blendRuntimeOptions(r.moxt);

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
                    if (v instanceof String s && s.contains("{{")) {
                        // Resolve aliases like "{{ownerId}}" to their actual values
                        localOverrides.put(k, Utils.Interpolation.interpolate(s, runningContext));
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
            MoxtureResolver.validateMoxture(finalSpec);
            boolean isAllowFailure = finalSpec.getOptions().isAllowFailure();

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
                    // ---> THE PRISTINE CALL <---
                    // The flags have been removed from the signature. 
                    // The executor pulls its behavior directly from finalSpec.getOptions()
                    return this.moxter.executor.execute(finalSpec, r.baseDir, mergedVars, jsonPathLax);
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
         * Blends the static YAML moxture options with the runtime Java API overrides.
         * Creates a shallow clone to avoid mutating the engine's cached definitions.
         */
        private Model.Moxture blendRuntimeOptions(Model.Moxture staticMoxt) {
            Model.Moxture finalSpec = MoxtureResolver.cloneWithoutBasedOn(staticMoxt);
            
            Model.RootOptionsDef staticOpts = finalSpec.getOptions();
            Model.RootOptionsDef mergedOpts = new Model.RootOptionsDef();
            
            // Logical OR: If either the YAML or the Java API says true, the final spec is true.
            mergedOpts.setAllowFailure(this.allowFailure || (staticOpts != null && staticOpts.isAllowFailure()));
            mergedOpts.setVerbose     (this.verbose      || (staticOpts != null && staticOpts.isVerbose()));

            finalSpec.setOptions(mergedOpts);
            return finalSpec;
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
                MoxtureResolver.EffectiveMoxture resolved = this.moxter.moxResolver.resolveByName(moxtureName);
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

        // ############################################################################

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

        // ############################################################################

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


    }

    // ############################################################################

    /**
     * Represents the outcome of a moxture execution.
     * Provides a fluent interface for inspecting the response and extracting data.
     */
    public static class MoxtureResult 
    {
        private final Runtime.ResponseEnvelope envelope;
        private final String moxtureName;
        // Reference to the encompassing engine
        private final Moxter moxter;

        /**
         * @param envelope    The response wrapper containing status, body, and raw content.
         * @param moxtureName The name of the moxture that produced this result (for error context).
         * @param moxter      The encompassing Engine.
         */
        public MoxtureResult(Runtime.ResponseEnvelope envelope, Moxter moxter, String moxtureName) {
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
        public Runtime.ResponseEnvelope getEnvelope() {
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
            Object actual = moxter.vars().get(varName);
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


        /**
         * Performs fluent assertions on a raw JsonPath from the response using a consumer,
         * returning this {@link MoxtureResult} to allow for further chaining.
         * 
         * <p>This allows one-off inspections of the response without needing to 
         * define a variable in the 'save' section (or even not having a 'save' section
         * alltogether)in the YAML moxture.
         * 
         * <p><b>Example Usage:</b>
         * <pre>{@code
         *      mx.call("get_pet")
         *        .assertJsonPath("$.species.name", x -> x.isEqualTo("Dog"));
         * }</pre>
         *
         * @param jsonPath     The JsonPath expression to extract from the response.
         * @param requirements A consumer that defines the AssertJ requirements for the extracted value.
         * @return This {@link MoxtureResult} for continued chaining.
         */
        public MoxtureResult assertJsonPath(String jsonPath, Consumer<ObjectAssert<Object>> requirements) 
        {
            // We reuse the existing assertThat(String) logic which handles extraction 
            // and AssertJ wrapping, including the beautiful .as() error descriptions.
            requirements.accept(this.assertThat(jsonPath));
            return this;
        }
    }










    // ############################################################################


    /**
     * Internal namespace for components that handle the <b>execution phase</b> of a moxture.
     * 
     * <p>Once a moxture definition has been discovered and fully materialized/'effectived' 
     * (flattened), the classes in this namespace take over to perform the actual test execution. 
     * Their responsibilities include:
     * <ul>
     *   <li><b>Templating:</b> Interpolating dynamic variables into endpoints, headers, and payloads.</li>
     *   <li><b>Payload Resolution:</b> Parsing and resolving complex or external JSON/YAML request bodies.</li>
     *   <li><b>HTTP Execution:</b> Translating the moxture into a Spring MockMvc request and firing it.</li>
     *   <li><b>Response Handling:</b> Capturing the raw network response and packaging it into an internal envelope.</li>
     * </ul>
     * 
     * <p><b>Note:</b> Classes within this namespace are strictly internal to the Moxter engine 
     * and should never be accessed or instantiated directly by test code.
     */
    static final class Runtime {

        /**
         * A strategy interface for interpolating dynamic variables into strings.
         *
         * <p>This engine is responsible for finding placeholders (e.g., {@code {{varName}}}) 
         * in the moxture definition and replacing them with their actual values from the 
         * provided variable context.
         */
        interface Templating {
            
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
        }

        /**
         * Simple {{var}} string substitution. 
         */
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


        // ############################################################################

        /**
         * Resolves raw payload definitions into finalized JSON nodes by processing variable 
         * interpolation, classpath resource loading, and structural recursion.
         * 
         * <p>The resolver handles two primary formats:
         * <ul>
         * <li><b>String/Scalar:</b> Can be a "classpath:..." reference, a raw JSON string, 
         * or a plain text body. All variables are interpolated before parsing.</li>
         * <li><b>Structural:</b> A YAML map or array. The resolver recursively walks 
         * the tree and applies templating to every string leaf node.</li>
         * </ul>
         */
        static final class PayloadResolver 
        {
            private final ObjectMapper mapper;

            /**
             * @param mapper The Jackson mapper (typically a YAML-aware instance) used 
             * to parse strings and manipulate nodes.
             */
            PayloadResolver(ObjectMapper mapper) { this.mapper = mapper; }

            /**
             * The new entry point that handles the inheritance stack.
             */
            JsonNode resolve(Model.Moxture spec, String baseDir, Map<String, Object> vars, Templating tpl) throws IOException {
                List<JsonNode> stack = spec.getBodyStack();
                
                // If there's no stack (shouldn't happen with our new materializeDeep), 
                // fallback to the single body.
                if (stack == null || stack.isEmpty()) {
                    return resolveSingleNode(spec.getBody(), baseDir, vars, tpl);
                }

                JsonNode effective = null;
                for (JsonNode layer : stack) {
                    // 1. Resolve THIS specific layer using your original logic
                    // This ensures {{ownerId}} is replaced BEFORE parsing.
                    JsonNode resolvedLayer = resolveSingleNode(layer, baseDir, vars, tpl);
                    
                    // 2. Deep merge it into the accumulated result.
                    // If 'effective' is null (first layer), deepMerge returns 'resolvedLayer'.
                    effective = MoxtureResolver.deepMergePayload(mapper, effective, resolvedLayer);
                }
                return effective;
            }

            /**
             * The main entry point for payload resolution.
             * 
             * It handles: Interpolation -> Classpath -> JSON Sniffing -> Parsing
             * 
             * @param payload The raw JsonNode from the YAML moxture definition.
             * @param baseDir The directory of the current moxture file (used for relative classpath resolution).
             * @param vars    The variable context for interpolation.
             * @param tpl     The templating engine for macro expansion.
             * @return A finalized {@link JsonNode} ready for HTTP execution.
             * @throws IOException If a classpath resource cannot be read or JSON is malformed.
             */
            private JsonNode resolveSingleNode(JsonNode payload, String baseDir, Map<String, Object> vars, Templating tpl) throws IOException {
                if (payload == null) return null;

                if (payload.isTextual()) {
                    String txt = payload.asText().trim();
                    
                    // Interpolate variables FIRST (makes the JSON valid!)
                    txt = Utils.Interpolation.interpolate(txt, vars); 
                    
                    // Handle 'classpath:'
                    String lower = txt.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("classpath:")) {
                        String rawPath = txt.substring(txt.indexOf(':') + 1).trim();
                        JsonNode fileContent = loadClasspathPayload(baseDir, rawPath);
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

                return templateNodeStrings(payload, vars, tpl);
            }


            /**
             * Loads a YAML or JSON file from the classpath and returns it as a JsonNode.
             */
            private JsonNode loadClasspathPayload(String baseDir, String rawPath) throws IOException {
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


        /**
         * The primary engine responsible for executing Moxture definitions against a MockMvc instance.
         * 
         * <p>The execution lifecycle follows a strict sequence:
         * <ol>
         *   <li><b>Resolution:</b> Variables and templates in the URL, headers, and body are resolved.</li>
         *   <li><b>Payload Preparation:</b> External resources (classpath) are loaded if specified.</li>
         *   <li><b>Request Construction:</b> A {@link MockHttpServletRequestBuilder} is initialized with 
         *       the appropriate method, URI, and security context (CSRF, Authentication).</li>
         *   <li><b>Execution:</b> The request is dispatched via {@code MockMvc.perform()}.</li>
         *   <li><b>State Management:</b> Response data (JSON body, headers) is extracted and stored 
         *       back into the variable context for use by subsequent moxtures.</li>
         *   <li><b>Validation:</b> Status codes and body contents are asserted against the 
         *       {@code expect} block.</li>
         * </ol>
         */
        @Slf4j
        static final class HttpExecutor 
        {
            private final MockMvc mockMvc;
            private final ObjectMapper jsonMapper;  // to send the payload as JSON
            private final Templating tpl;
            private final PayloadResolver payloads;
            private final StatusMatcher statusMatcher;
            private final java.util.function.Supplier<org.springframework.security.core.Authentication> authSupplier;

            HttpExecutor(MockMvc mockMvc, ObjectMapper jsonMapper, Templating tpl,
                         PayloadResolver payloads, StatusMatcher statusMatcher,
                         java.util.function.Supplier<org.springframework.security.core.Authentication> authSupplier) {
                this.mockMvc = mockMvc;
                this.jsonMapper = jsonMapper;
                this.tpl = tpl;
                this.payloads = payloads;
                this.statusMatcher = statusMatcher;
                this.authSupplier = authSupplier;
            }


            /**
             * Execute a single moxture call.
             * Logs a concise start line, rich DEBUG details, response preview, a finish line with duration,
             * and a compact warning when the expected status does not match.
             *
             * @param spec
             * @param baseDir
             * @param vars
             * @param jsonPathLax if true, the library that reads JSON paths in the "save" in the yaml moxtures
             * will have a lax (aka lenient) configuration.
             */
            Runtime.ResponseEnvelope execute(Model.Moxture spec, 
                                             String baseDir, 
                                             Map<String,Object> vars,
                                             boolean jsonPathLax) 
            {
                final long t0 = System.nanoTime();
                final String name   = (spec.getName() == null || spec.getName().isBlank()) ? "<unnamed>" : spec.getName();
                final String method = Utils.Http.safeMethod(spec.getMethod());
                
                // Read allowFailure policy directly from the effective spec
                final boolean allowFailure = spec.getOptions() != null && spec.getOptions().isAllowFailure();
                final boolean verbose      = spec.getOptions() != null && spec.getOptions().isVerbose();

                // Resolve JsonPath Configuration from the parameter
                final Configuration jsonPathConfig = jsonPathLax ? JSONPATH_CONF_LAX : JSONPATH_CONF_STRICT;

                try {
                    // 1. Prepare URI, Headers, and Payload
                    if (spec.getEndpoint() == null || spec.getEndpoint().isBlank()) {
                        throw new IllegalArgumentException("Moxture '" + name + "' has no 'endpoint'.");
                    }
                    final String endpoint = tpl.apply(spec.getEndpoint(), vars);
                    final Map<String,String> headers0 = tpl.applyMapValuesOnly(spec.getHeaders(), vars);
                    final Map<String,String> query    = tpl.applyMapValuesOnly(spec.getQuery(), vars);
                    final URI uri = URI.create(Utils.Http.appendQuery(endpoint, query));
                    final JsonNode payloadNode = payloads.resolve(spec, baseDir, vars, tpl);

                    logExecutionStart(verbose, name, method, uri, headers0, query, vars, payloadNode);

                    // 2. Build the Spring MockMvc Request
                    MockHttpServletRequestBuilder req = buildRequest(spec, baseDir, vars, method, uri, headers0, payloadNode);

                    // 3. Execute HTTP Call & Parse Response
                    MockHttpServletResponse mvcResp = mockMvc.perform(req).andDo(print()).andReturn().getResponse();
                    Runtime.ResponseEnvelope env = parseResponse(mvcResp);

                    logResponsePreview(verbose, env);

                    // 4. Save Variables for future moxtures FIRST
                    // (So if a moxture is 'allowFailure: true', it still extracts what it can before any potential assertion failures)
                    processSaves(spec, env, vars, name, jsonPathConfig);

                    // 5. Verify Expectations
                    try {
                        verifyExpectations(spec, env, baseDir, vars, name, method, uri, jsonPathConfig);
                    } catch (AssertionError e) {
                        // The single source of truth for the allowFailure policy
                        if (allowFailure) {
                            log.info("[Moxter] (allowFailure mode allowed failure): {}", e.getMessage());
                        } else {
                            throw e; 
                        }
                    }

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

            // =========================================================================
            //  Phase 1: Request Building
            // =========================================================================

            private MockHttpServletRequestBuilder buildRequest(Model.Moxture spec, String baseDir, Map<String,Object> vars, 
                                                               String method, URI uri, Map<String,String> headers0, 
                                                               JsonNode payloadNode) throws Exception 
            {
                MockHttpServletRequestBuilder req;
                Map<String,String> headers = (headers0 == null) ? new LinkedHashMap<>() : new LinkedHashMap<>(headers0);

                if (spec.getMultipart() != null && !spec.getMultipart().isEmpty()) {
                    req = buildMultipartRequest(spec, baseDir, vars, method, uri, headers);
                } else {
                    req = Utils.Http.toRequestBuilder(method, uri);
                    if (payloadNode != null) {
                        req.content(jsonMapper.writeValueAsBytes(payloadNode));
                        if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
                            req.contentType(MediaType.APPLICATION_JSON);
                        }
                    }
                }

                // Attach Auth & CSRF
                attachSecurity(req, method);

                // Attach standard headers
                for (Map.Entry<String,String> e : headers.entrySet()) {
                    req.header(e.getKey(), e.getValue());
                }
                return req;
            }

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
                        JsonNode resolvedPartBody = payloads.resolveSingleNode(part.body, baseDir, vars, tpl);
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

            private void attachSecurity(MockHttpServletRequestBuilder req, String method) {
                org.springframework.security.core.Authentication auth = null;
                if (authSupplier != null) {
                    try { auth = authSupplier.get(); } catch (Exception ignore) {}
                }
                if (auth == null) {
                    auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                }
                if (auth != null) {
                    req.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(auth));
                    log.debug("[Moxter] using Authentication principal={}", Utils.Misc.safeName(auth));
                }
                if (Utils.Http.requiresCsrf(method)) {
                    req.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf());
                    log.debug("[Moxter] CSRF token added for {}", method);
                }
            }

            // =========================================================================
            //  Phase 2: Response Parsing
            // =========================================================================

            private Runtime.ResponseEnvelope parseResponse(MockHttpServletResponse mvcResp) throws Exception {
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
                return new Runtime.ResponseEnvelope(mvcResp.getStatus(), Utils.Http.copyHeaders(mvcResp), body, raw);
            }

            // =========================================================================
            //  Phase 3: Verification (Expectations)
            // =========================================================================

            private void verifyExpectations(Model.Moxture spec, Runtime.ResponseEnvelope env, String baseDir, Map<String,Object> vars, 
                                            String name, String method, URI uri, Configuration jsonPathConfig) throws Exception 
            {
                if (spec.getExpect() == null) return;

                // 1. Check Status
                if (spec.getExpect().getStatus() != null) {
                    if (!statusMatcher.matches(spec.getExpect().getStatus(), env.status())) {
                        String bodyPreview = (env.raw() == null || env.raw().isBlank()) ? "<empty>" : Utils.Logging.truncate(env.raw(), 500);
                        String msg = String.format(Locale.ROOT, "Unexpected HTTP %d for '%s' %s %s, expected=%s. Body=%s",
                                env.status(), name, method, uri, expectedStatusPreview(spec.getExpect().getStatus()), bodyPreview);
                        
                        // Natively throw it. The execute() method catches it and handles the allowFailure policy.
                        throw new AssertionError(msg);
                    }
                }

                // 2. Check Body Assertions
                Model.ExpectBodyDef bodyDef = spec.getExpect().getBody();
                if (bodyDef == null) return;

                // 2a. Match
                if (bodyDef.getMatch() != null) {
                    verifyMatch(bodyDef.getMatch(), env, baseDir, vars, name, jsonPathConfig);
                }

                // 2b. Assert
                if (bodyDef.getAssertDef() != null && !bodyDef.getAssertDef().isEmpty()) {
                    // We pass the 'spec' down so it can read the allowFailure flag for the Hybrid Short-Circuit
                    verifySurgicalAsserts(spec, bodyDef.getAssertDef(), env, baseDir, vars, jsonPathConfig);
                }
            }

            private void verifyMatch(Model.ExpectBodyMatchDef matchDef, Runtime.ResponseEnvelope env, String baseDir, 
                                     Map<String,Object> vars, String name, Configuration jsonPathConfig) throws Exception 
            {
                try {
                    if (env.raw() == null || env.raw().isBlank()) throw new AssertionError("Response body is empty.");
                    
                    JsonNode expectedNode = payloads.resolveSingleNode(matchDef.getContent(), baseDir, vars, tpl);
                    String expectedJsonStr = jsonMapper.writeValueAsString(expectedNode);
                    String actualJsonStr = env.raw();

                    if (matchDef.getIgnorePaths() != null && !matchDef.getIgnorePaths().isEmpty()) {
                        DocumentContext actCtx = JsonPath.using(jsonPathConfig).parse(actualJsonStr);
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

            private void verifySurgicalAsserts(Model.Moxture spec, Model.ExpectBodyAssertDef assertDef, Runtime.ResponseEnvelope env, 
                                               String baseDir, Map<String,Object> vars, Configuration jsonPathConfig) throws Exception 
            {
                // Read policy from the spec for the short-circuit logic
                final boolean allowFailure = spec.getOptions() != null && spec.getOptions().isAllowFailure();

                
                DocumentContext actCtx = com.jayway.jsonpath.JsonPath.using(jsonPathConfig).parse(env.raw());
                List<String> collectedErrors = new ArrayList<>();
                
                // Using a standard for-loop to cleanly handle exceptions and control flow
                for (Map.Entry<String, JsonNode> entry : assertDef.getPaths().entrySet()) {
                    String path = entry.getKey();
                    JsonNode rawExpectedNode = entry.getValue();
                    
                    try {
                        com.fasterxml.jackson.databind.JsonNode expectedNode = payloads.resolveSingleNode(rawExpectedNode, baseDir, vars, tpl);
                        Object actualValue = actCtx.read(path);
                        
                        if (expectedNode.isNull()) {
                            org.assertj.core.api.Assertions.assertThat(actualValue).as("Path '%s'", path).isNull();
                        } else if (expectedNode.isNumber()) {
                            org.assertj.core.api.Assertions.assertThat(((Number) actualValue).doubleValue())
                                    .as("Path '%s'", path).isEqualTo(expectedNode.asDouble());
                        } else if (expectedNode.isBoolean()) {
                            org.assertj.core.api.Assertions.assertThat(actualValue)
                                    .as("Path '%s'", path).isEqualTo(expectedNode.asBoolean());
                        } else if (expectedNode.isContainerNode()) {
                            String expectedJson = jsonMapper.writeValueAsString(expectedNode);
                            String actualJson = jsonMapper.writeValueAsString(actualValue);
                            org.skyscreamer.jsonassert.JSONAssert.assertEquals(expectedJson, actualJson, true);
                        } else {
                            org.assertj.core.api.Assertions.assertThat(String.valueOf(actualValue))
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

                    // ---> THE HYBRID SHORT-CIRCUIT <---
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

            // =========================================================================
            //  Phase 4: Save & Logging Helpers
            // =========================================================================

            private void processSaves(Model.Moxture spec, Runtime.ResponseEnvelope env, Map<String,Object> vars, String name, Configuration jsonPathConfig) {
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

            private void logExecutionStart(boolean verbose, String name, String method, URI uri, Map<String,String> headers, Map<String,String> query, Map<String,Object> vars, JsonNode payload) {
                log.info("[Moxter] >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
                log.info("[Moxter] >>> Executing moxture:  [{}, {}, {}]", name, method, uri);
                
                if (verbose || log.isDebugEnabled()) {
                    String msg = "[Moxter] more info: headers={} query={} vars={} payload={}";
                    Object[] args = {
                            Utils.Logging.previewHeaders(headers),
                            (query == null || query.isEmpty() ? "{}" : query.toString()),
                            Utils.Logging.previewVars(vars),
                            Utils.Logging.previewNode(payload)
                    };
                    // The bypass: if verbose is requested, force it out at INFO level
                    if (verbose) log.info(msg, args); else log.debug(msg, args);
                }
            }

            private void logResponsePreview(boolean verbose, Runtime.ResponseEnvelope env) {
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

            // =========================================================================
            // Helpers
            // =========================================================================
            private static String expectedStatusPreview(JsonNode expected) {
                if (expected == null || expected.isNull()) return "(none)";
                return expected.toString();
            }
        }



        /**
         * An internal, immutable container representing the raw HTTP response returned by 
         * the execution engine.
         *
         * <p>This class acts as a bridge between the underlying HTTP client (Spring MockMvc) 
         * and Moxter's public API. It captures the exact state of the network response 
         * immediately after execution, before it is wrapped in a user-friendly 
         * {@link MoxtureResult} for assertions.
         *
         * @param status  The HTTP status code returned by the server (e.g., 200, 404).
         * @param headers The HTTP response headers, mapped by header name to a list of values.
         * @param body    The response body parsed into a Jackson JSON tree (null if not JSON).
         * @param raw     The raw, unparsed string representation of the HTTP response body.
         */
        public record ResponseEnvelope(
            int status, 
            Map<String, List<String>> headers, 
            JsonNode body, 
            String raw
        ) {}



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
    }

    // ############################################################################
    
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
             * <ul>
             * <li><b>Type Safety:</b> Uses {@code String.valueOf(v)} to handle numbers and booleans.</li>
             * <li><b>Null Safety:</b> Replaces null values with the literal string "null".</li>
             * <li><b>Regex Safety:</b> Uses {@code Matcher.quoteReplacement} so that values 
             * containing special characters (like '$' or '\') don't break the interpolation.</li>
             * <li><b>Graceful Failure:</b> If a variable is missing, the original placeholder 
             * {@code {{key}}} is preserved in the output to help with debugging.</li>
             * </ul>
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
             * Used for binary payloads like images or PDFs.
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
             * Determines if a Content-Type header indicates a JSON payload.
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
             * Performs a fast, heuristic check to determine if a string loosely resembles a JSON payload.
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
        }

        @Getter @Setter
        public static final class RootOptionsDef {
            
            private boolean allowFailure = false;
            private boolean verbose = false;
        }

        @Getter @Setter
        public static class ExpectDef 
        {
            private JsonNode status;            // int | "2xx"/"3xx"/"4xx"/"5xx" | "201" | [ ... any of those ... ]
            private ExpectBodyDef body;
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
