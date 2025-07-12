package com.fhi.pet_clinic.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                .configure(JsonParser.Feature.ALLOW_COMMENTS, true)                       // // and /* */ comments
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);     // ignore _comment and other extras
    }
}
