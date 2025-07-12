package com.fhi.pet_clinic.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhi.pet_clinic.model.Owner;
import com.fhi.pet_clinic.model.Pet;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;


/**
 * Loads a predefined {owner and pet} json data file to easily retrieve Owner/Pet values.
 *
 * Loads a JSON file, the path of which is defined in application properties,
 * into memory during application startup.
 *
 * The relationships between Owner and Pet are deliberately not set:
 * - Owner.pets remains null or empty
 * - Pet.owner is not assigned
 *
 *
 * This component is useful for preloading a consistent object graph
 * for test scenarios or workshop demonstrations.
 */
@Component
public class DataLoader {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String jsonPath;

    private List<Owner> cache;

    public DataLoader(@Value("${data.owners.path}") String jsonPath) {
        this.jsonPath = jsonPath;
    }

    @PostConstruct
    private void init() {
        try {
            File file = new File(jsonPath);
            JsonNode root = objectMapper.readTree(file);
            List<Owner> result = new ArrayList<>();

            for (JsonNode ownerNode : root) {
                Owner owner = new Owner();
                owner.setName(ownerNode.get("name").asText());

                // DO NOT set pets on owner
                // DO NOT set owner on pet
                result.add(owner);
            }

            this.cache = result;

        } catch (Exception e) {
            throw new RuntimeException("Failed to load test data from JSON: " + jsonPath, e);
        }
    }

    /**
     * Returns an owner described in the  json data file.
     * The owner's pets are not set and must be linked manually.
     *
     * @param index of owner in the data file. Starts at 0.
     * @return an owner instance with no relationships set
     */
    public Owner getOwner(int index) {
        return cache.get(index);
    }


   /**
     * Returns a Pet as described in the json data file.
     * 
     * @param ownerIndex the owner of the pet. Index starts at 0.
     * @param petIndex index ot the pet in the owner's lit of pets. Sarts at 0.
     * @return
     */


    /**
     * Returns a Pet as described in the json data file.
     * The returned Pet is not linked to any Owner.
     *
     * @param ownerIndex the index of the owner (0-based)
     * @param petIndex the index of the pet in the owner's array (0-based)
     * @return a Pet instance with no owner set
     */
    public Pet getPet(int ownerIndex, int petIndex) {
        try {
            File file = new File(jsonPath);
            JsonNode root = objectMapper.readTree(file);

            JsonNode ownerNode = root.get(ownerIndex);
            JsonNode petNode = ownerNode.withArray("pets").get(petIndex);

            Pet pet = new Pet();
            pet.setName(petNode.get("name").asText());
            return pet;

        } catch (Exception e) {
            throw new RuntimeException("Failed to get pet from JSON", e);
        }
    }
}
