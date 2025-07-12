package com.fhi.pet_clinic.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhi.pet_clinic.model.Owner;
import com.fhi.pet_clinic.model.Pet;
import com.fhi.pet_clinic.repo.OwnerRepository;
import com.fhi.pet_clinic.repo.PetRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import com.fasterxml.jackson.databind.JavaType;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;



/**
 * Test configuration class used to preload test data into the database
 * for integration tests.
 *
 * <p>This class is automatically imported into the Spring test context
 * using @Import(...). It reads fixture data from JSON files and populates
 * the repositories with test entities.
 */
@TestConfiguration
public class TestDataLoader 
{
   // Since we're in a test class, we'll be ok with attribute injection 
   // instead of the usually recommended constructor injection ;o)
   @Autowired
   private OwnerRepository ownerRepository;

   @Autowired
   private PetRepository petRepository;

   @Autowired
   private ObjectMapper objectMapper;

   @PostConstruct
   public void loadTestData() throws IOException 
   {
      List<Owner> owners = loadListFromJson("fixtures/owners.json", Owner.class);
      ownerRepository.saveAll(owners);

      // After persisting the Owner entities, we can then save the Pet entities.
      // Remember that Pet owns the relationship, i.e. Pet has the FK to Owner.
      // This way, when the Pet entities refer to their owners, those owners already 
      // exist in the database.

      List<Pet> pets = loadListFromJson("fixtures/pets.json", Pet.class);
      petRepository.saveAll(pets);
   }



   /**
    * Loads a list of objects of type {@code T} from a JSON file located in the classpath.
    * <p>
    * The JSON file must contain a JSON array. Each element of the array will be deserialized
    * into an instance of the specified class {@code T}.
    * <p>
    * Example usage:
    * <pre>
    *   List&lt;Owner&gt; owners = fixtureLoader.loadListFromJson("fixtures/owners.json", Owner.class);
    * </pre>
    *
    * @param resourcePath the relative path to the JSON file in the classpath (e.g. {@code "fixtures/owners.json"})
    * @param clazz the class of the elements in the list
    * @param <T> the type of objects to deserialize
    * @return a list of deserialized objects of type {@code T}
    * @throws IllegalArgumentException if the resource file is not found
    * @throws RuntimeException if the deserialization fails
    */
    public <T> List<T> loadListFromJson(String resourcePath, Class<T> clazz) 
    {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) 
        {
            if (is == null) 
            {   throw new IllegalArgumentException("Resource not found: " + resourcePath);
            }

            // Correct way to tell Jackson the target is List<T>>
            JavaType listType = objectMapper.getTypeFactory()
                                            .constructCollectionType(List.class, clazz);

            return objectMapper.readValue(is, listType);
        } 
        catch (Exception e) 
        {   throw new RuntimeException("Failed to load list of elements from json: " + resourcePath, e);
        }
    }


}
