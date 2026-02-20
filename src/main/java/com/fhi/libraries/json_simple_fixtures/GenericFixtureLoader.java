package com.fhi.libraries.json_simple_fixtures;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;



/**
 * Generic JSON-based fixture loader for integration tests.
 *
 * <p>This component loads entity lists from classpath JSON files into the database by resolving the
 * appropriate Spring Data {@link CrudRepository} for the target entity type. It supports a simple
 * two-tier lookup strategy for fixture files:
 *
 * <ul>
 *   <li><b>Test-specific path</b>: {@code /resources/json_simple_fixtures/tests/{TestClassSimpleName}/{entityName}.json}</li>
 *   <li><b>Shared path</b>: {@code /resources/json_simple_fixtures/shared/{entityName}.json}</li>
 * </ul>
 *
 * Where {@code entityName} is computed from the entity class as {@code entitySimpleName.toLowerCase() + ".json"}.
 *
 * <p>Example for {@code Owner.class} inside test class {@code MyIntegrationTest}:
 * <pre>
 * /resources/json_simple_fixtures/tests/MyIntegrationTest/owner.json
 * /resources/json_simple_fixtures/shared/owner.json
 * </pre>
 *
 * <h3>Transactions</h3>
 * <p>{@link #load(Class, String)} is annotated with {@link Transactional}; when used in Spring tests with
 * transactional test methods, saved fixtures participate in the test transaction and will be rolled back
 * at the end of each test (with default {@code Propagation.REQUIRED}). If your code under test uses
 * {@code REQUIRES_NEW} or manual transaction templates, those inner transactions may commit independently.
 * See your test strategy notes for mitigations.
 *
 * <h3>Thread-safety</h3>
 * <p>The component is stateless except for a thread-safe cache of resolved repositories.
 */
@Slf4j
@Component
public class GenericFixtureLoader 
{
    /**
     * Root folder under {@code src/test/resources} (or {@code src/main/resources} if shared),
     * where fixture trees are stored.
     *
     * <p>Expected layout:
     * <pre>
     * json_simple_fixtures/
     * ├─ shared/
     * │   ├─ owner.json
     * │   └─ pet.json
     * └─ tests/
     *     └─ MyIntegrationTest/
     *         ├─ owner.json
     *         └─ pet.json
     * </pre>
     */
    private static final String ROOT_FOLDER="json_simple_fixtures";


    private final ObjectMapper objectMapper;
    private final ApplicationContext context;

    /**
     * Cache of Spring Data repositories keyed by their entity type.
     * <p>Speeds up repeated fixture loads in the same test run.</p>
     */
    private final Map<Class<?>, CrudRepository<?, ?>> repositoryCache = new ConcurrentHashMap<>();

    // Autowired constructor
    public GenericFixtureLoader(ObjectMapper objectMapper, ApplicationContext context) 
    {
        this.objectMapper = objectMapper;
        this.context = context;
    }


    /**
     * Loads and persists a list of entities of type {@code T} from JSON into the database.
     *
     * <p>The method searches for a JSON file named after the entity simple name (lower-cased)
     * with a {@code .json} extension (e.g., {@code Owner -> owner.json}) in the following order:
     *
     * <ol>
     *   <li>{@code json_simple_fixtures/tests/{testClassSimpleName}/{entityName}}</li>
     *   <li>{@code json_simple_fixtures/shared/{entityName}}</li>
     * </ol>
     *
     * The first existing file is deserialized into {@code List<T>} and saved with the resolved
     * {@link CrudRepository} for {@code T}.
     *
     * <p><b>Transaction semantics:</b> The method runs within a Spring transaction. In test contexts
     * where the test method is also transactional (default {@code REQUIRED}), the inserted fixtures
     * will roll back at test end. Be mindful of inner {@code REQUIRES_NEW} or async code paths in the
     * application under test, which may commit independently.
     *
     * @param entityClass          entity type to load (e.g., {@code Owner.class})
     * @param testClassSimpleName  the simple name of the current test class, used to build the test-specific path
     * @param <T>                  entity type parameter
     *
     * @throws RuntimeException if no fixture file is found in either location, or if deserialization fails
     * @throws org.springframework.dao.DataIntegrityViolationException if persistence violates DB constraints
     */
    @Transactional // needed because we're using a JPA save operation
    public <T> void load(Class<T> entityClass, String testClassSimpleName) 
    {   log.debug("");

        String entityName = entityClass.getSimpleName().toLowerCase() + ".json";  // e.g. Owner -> owners.json
        log.debug("entityName = {}", entityName);

        List<String> candidatePaths = List.of(
                ROOT_FOLDER + "/tests/" + testClassSimpleName + "/" + entityName,
                ROOT_FOLDER + "/shared/" + entityName
        );

        for (String path : candidatePaths) 
        {   log.debug("atttempting to load candidatePaths = {}", path);
            try (InputStream is = new ClassPathResource(path).getInputStream()) 
            {   List<T> entities;
                try 
                {   entities = deserializeList(is, entityClass);
                }
                catch (Exception e) 
                {   throw new RuntimeException(
                            String.format("While trying to deserialize entities of type %s from path %s", 
                                          entityClass.getSimpleName(), path), e);
                }
                log.debug("About to save entities of type {} ...", entityClass.getSimpleName());
                getRepository(entityClass).saveAll(entities);
                log.info("Save in DB DONE. Loaded {} entities of type {} from {}", entities.size(), entityClass.getSimpleName(), path);
                return; // success!
            } 
            catch (java.io.FileNotFoundException e) 
            {
                log.debug("Fixture file not found: {}: {}", path, e.getMessage());
                log.debug("Trying next one");
                // Try next one silently
            } 
            catch (IOException e) 
            {
                log.warn("I/O error while loading fixture from {}: {}", path, e.getMessage());
            }
            catch (org.springframework.dao.DataIntegrityViolationException e)
            {
                log.error("Data integrity violation while saving fixture from {}: {}", path, e.getMessage());
                // TODO wrap
                throw e;
            }
        }

        throw new RuntimeException("No fixture file found for entity: " + entityClass.getSimpleName() +
                                   " (looked in: " + candidatePaths + ")");
    }


    /**
     * Resolves (and caches) the Spring Data {@link CrudRepository} matching the given entity class.
     *
     * <p>This performs a type-parameter inspection against all {@code CrudRepository} beans in the context,
     * selecting the first whose entity generic matches {@code entityClass}. The resolution is cached for
     * subsequent calls.</p>
     *
     * @param entityClass the entity type whose repository is required
     * @param <T>         the entity generic type
     * @return a {@code CrudRepository<T, ?>} bean
     * @throws IllegalArgumentException if no repository bean matching {@code entityClass} is found
     */
    @SuppressWarnings("unchecked")
    private <T> CrudRepository<T, ?> getRepository(Class<T> entityClass) {
        return (CrudRepository<T, ?>) repositoryCache.computeIfAbsent(entityClass, cls -> {
            // Lookup Spring bean by type assignable to CrudRepository<T, ?>
            return context.getBeansOfType(CrudRepository.class)
                    .values()
                    .stream()
                    .filter(repo -> {
                        JavaType repoType = objectMapper.getTypeFactory().constructType(repo.getClass());
                        JavaType[] generics = objectMapper.getTypeFactory()
                                .findTypeParameters(repoType, CrudRepository.class);
                        return generics.length > 0 && generics[0].getRawClass().equals(cls);
                    })
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No repository found for " + cls.getName()));
        });
    }


    /**
     * Deserializes an input stream containing a JSON array into a {@code List<T>}.
     *
     * <p>Example JSON:
     * <pre>
     * [
     *   { "id": 1, "name": "John Doe" },
     *   { "id": 2, "name": "Jane Doe" }
     * ]
     * </pre>
     *
     * @param is    input stream of a JSON array
     * @param clazz entity class to deserialize into
     * @param <T>   entity type
     * @return list of deserialized entities
     * @throws Exception if deserialization fails
     */
    private <T> List<T> deserializeList(InputStream is, Class<T> clazz) throws Exception {
        JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, clazz);
        return objectMapper.readValue(is, listType);
    }
}
