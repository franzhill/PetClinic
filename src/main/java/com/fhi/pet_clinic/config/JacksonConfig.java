package com.fhi.pet_clinic.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig 
{
   /**
    * Provides a customized {@link ObjectMapper}
    * - allowing JSON comments 
    * - and other configurations
    * 
    * This particular object mapper will be default one injected wherever 
    *   @Autowired
    *   private ObjectMapper objectMapper;  // <= this objectMapper defined below
    * is used.
    */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .configure(JsonParser.Feature.ALLOW_COMMENTS, true)                       // // and /* */ comments in JSON
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)     // allows _comment fields etc.
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)                 // write "2025-07-15", not [2025,7,15]
                .registerModule(new JavaTimeModule())                                    // support for java.time.* (Java 8 "modern" time types)
                .enable(SerializationFeature.INDENT_OUTPUT);                             // for pretty print
    }
}
