package com.fhi.pet_clinic.fixtures;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class GenericFixtureLoader {

    private final ObjectMapper objectMapper;
    private final ApplicationContext context;

    // Cache repositories by entity type
    private final Map<Class<?>, CrudRepository<?, ?>> repositoryCache = new ConcurrentHashMap<>();

    public GenericFixtureLoader(ObjectMapper objectMapper, ApplicationContext context) {
        this.objectMapper = objectMapper;
        this.context = context;
    }


    @Transactional  // needed because we're using a JPA save operation
    public <T> void load(Class<T> entityClass, String testClassSimpleName) 
    {   log.debug("");

        String entityName = entityClass.getSimpleName().toLowerCase() + "s.json";  // e.g. Owner -> owners.json

        List<String> candidatePaths = List.of(
                "fixtures/tests/" + testClassSimpleName + "/" + entityName,
                "fixtures/shared/" + entityName
        );

        for (String path : candidatePaths) 
        {
            try (InputStream is = new ClassPathResource(path).getInputStream()) 
            {   List<T> entities = deserializeList(is, entityClass);
                getRepository(entityClass).saveAll(entities);
                log.info("Loaded {} entities of type {} from {}", entities.size(), entityClass.getSimpleName(), path);
                return; // success!
            } 
            catch (Exception e) 
            {   // Silent fail — try next
                log.debug("Failed to load fixture from {}: {}", path, e.getMessage());
                log.debug("Trying next one");
            }
        }

        throw new RuntimeException("No fixture file found for entity: " + entityClass.getSimpleName() +
                                   " (looked in: " + candidatePaths + ")");
    }


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

    private <T> List<T> deserializeList(InputStream is, Class<T> clazz) throws Exception {
        JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, clazz);
        return objectMapper.readValue(is, listType);
    }
}
