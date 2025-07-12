package com.fhi.pet_clinic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootApplication
public class PetClinicApplication 
{

    /**
     * Run with live reload (if you're doing dev)
     * $ mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dspring.devtools.restart.enabled=true"
     */
    public static void main(String[] args) 
    {
        SpringApplication.run(PetClinicApplication.class, args);
        log.info("HELLO RUNNING THE APP");
    }
}