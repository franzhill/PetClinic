package com.fhi.pet_clinic.api.exception;

import com.fhi.pet_clinic.service.exception.pet.MatingException;
import com.fhi.pet_clinic.service.exception.pet.MatingException.Cause;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class MatingExceptionHandler 
{
    @ExceptionHandler(MatingException.class)
    public ResponseEntity<Map<String, Object>> handleMatingException(MatingException ex) 
    {
        HttpStatus status = mapCauseToStatus(ex.getCauseEnum());

        return ResponseEntity.status(status).body(Map.of(
            "timestamp", Instant.now().toString(),
            "code", ex.getCauseEnum().getCode(),
            "message", ex.getMessage()
        ));
    }

    private HttpStatus mapCauseToStatus(Cause cause) {
        return switch (cause) 
        {
            case OUTSIDE_FERTILITY_WINDOW,
                 STERILE_PARENT,
                 SAME_SEX,
                 INSUFFICIENT_MATURITY,
                 SPECIES_MISMATCH,
                 EXCESSIVE_DEGENERACY -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PARENT_NOT_FOUND     -> HttpStatus.NOT_FOUND;
            case UNKNOWN              -> HttpStatus.INTERNAL_SERVER_ERROR;
            //#case null    -> HttpStatus.INTERNAL_SERVER_ERROR;
         };
    }
}
