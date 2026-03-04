package com.fhi.pet_clinic.utils;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class LogUtils {

    // 1. Define a MixIn that enforces Identity handling (prevents cycles)
    @JsonIdentityInfo(
        generator = ObjectIdGenerators.IntSequenceGenerator.class, 
        property = "@refId"
    )
    private interface CycleHandlingMixin {}

    // Static Mapper: Thread-safe, instantiated once.
    // Configured to handle Java 8 dates (Instant, ZonedDateTime) correctly.
    private static final ObjectMapper MAPPER = new ObjectMapper();


    static {
        MAPPER.registerModule(new JavaTimeModule());
        MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        
        // FAIL-SAFE: Verify that we don't crash on empty beans (common in Hibernate proxies)
        MAPPER.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

        // MAGIC FIX: Apply the CycleHandlingMixin to ALL objects.
        // This forces Jackson to replace circular references with a simple "@refId": 1
        // instead of looping infinitely.
        MAPPER.addMixIn(Object.class, CycleHandlingMixin.class);
    }

    /**
     * Serializes an object to a pretty-printed JSON String.
     * 
     * Handles cycles safely.
     * Safe to use in log statements: returns a fallback error string instead of throwing exceptions.
     *
     * @param object The object to serialize
     * @return The JSON string, or "null" if object is null, or an error message if serialization fails.
     */
    public static String toJson(Object object) {
        if (object == null) {
            return "null";
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(object);
        } catch (JsonProcessingException e) {
            // Fallback that shouldn't happen often with the CycleHandlingMixin
            return "<Error serializing object: " + e.getMessage() + ">";
        }
    }

    public static String getUniqueIdHash(Object obj)
    {   return Integer.toHexString(System.identityHashCode(obj));
    }

}
