package com.fhi.pet_clinic.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fhi.pet_clinic.model.Customer;
import com.fhi.pet_clinic.model.Pet;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;


/**
 * Loads a predefined {customer and pet} json data file to easily retrieve Customer/Pet values.
 *
 * Loaded from a JSON file the path of which is defined in application properties
 * into memory during application startup.
 *
 * The relationships between Customer and Pet are deliberately not set:
 * - Customer.pets remains null or empty
 * - Pet.customer is not assigned
 *
 * This is intended for demonstrating the importance of correctly setting
 * owning/inverse sides in JPA bidirectional relationships.
 *
 * This component is useful for preloading a consistent object graph
 * for test scenarios or workshop demonstrations.
 */
@Component
public class DataLoader {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String jsonPath;

    private List<Customer> cache;

    public DataLoader(@Value("${data.customers.path}") String jsonPath) {
        this.jsonPath = jsonPath;
    }

    @PostConstruct
    private void init() {
        try {
            File file = new File(jsonPath);
            JsonNode root = objectMapper.readTree(file);
            List<Customer> result = new ArrayList<>();

            for (JsonNode customerNode : root) {
                Customer customer = new Customer();
                customer.setName(customerNode.get("name").asText());

                // DO NOT set pets on customer
                // DO NOT set customer on pet
                result.add(customer);
            }

            this.cache = result;

        } catch (Exception e) {
            throw new RuntimeException("Failed to load test data from JSON: " + jsonPath, e);
        }
    }

    /**
     * Returns a Customer described in the  json data file.
     * The customer's pets are not set and must be linked manually.
     *
     * @param index of customer in the data file. Starts at 0.
     * @return a Customer instance with no relationships set
     */
    public Customer getCustomer(int index) {
        return cache.get(index);
    }


   /**
     * Returns a Pet as described in the json data file.
     * 
     * @param customerIndex the customer of the pet. Index starts at 0.
     * @param petIndex index ot the pet in the customer's lit of pets. Sarts at 0.
     * @return
     */


    /**
     * Returns a Pet as described in the json data file.
     * The returned Pet is not linked to any Customer.
     *
     * @param customerIndex the index of the customer (0-based)
     * @param petIndex the index of the pet in the customer's array (0-based)
     * @return a Pet instance with no customer set
     */
    public Pet getPet(int customerIndex, int petIndex) {
        try {
            File file = new File(jsonPath);
            JsonNode root = objectMapper.readTree(file);

            JsonNode customerNode = root.get(customerIndex);
            JsonNode petNode = customerNode.withArray("pets").get(petIndex);

            Pet pet = new Pet();
            pet.setName(petNode.get("name").asText());
            return pet;

        } catch (Exception e) {
            throw new RuntimeException("Failed to get pet from JSON", e);
        }
    }
}
