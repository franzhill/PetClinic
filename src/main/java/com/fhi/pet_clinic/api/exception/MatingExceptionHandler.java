package com.fhi.pet_clinic.api.exception;

import com.fhi.pet_clinic.service.exception.pet.MatingException;
import com.fhi.pet_clinic.service.exception.pet.MatingException.Cause;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;


/**
 * Global exception handler for MatingException, used to translate domain-specific
 * mating errors into meaningful HTTP responses.
 * 
 * This class lives in the api.exception package (rather than next to MatingException
 * in the service.exception.pet package) because it represents an application-layer concern:
 * mapping domain logic failures to REST-level semantics. It is part of the boundary between the
 * internal service layer and the external API contract.
 *
 * Key design rationale:
 *  - MatingException belongs to the service/domain layer — it expresses a business rule violation.
 *  - MatingExceptionHandler belongs to the API layer — it handles how exceptions are exposed to clients.
 *  - This separation respects clean architecture / layered architecture principles by avoiding 
 *    leakage of HTTP concerns into the domain layer.
 *  - Placing it under api.exception makes the boundary layer responsibilities more discoverable 
 *    and cohesive.
 * 
 * This class is annotated with RestControllerAdvice, which allows it to catch exceptions
 * thrown by any controller and return custom responses instead of letting Spring's default
 * exception handling take over.
 * 
 * Why this is useful:
 *  - Decouples error reporting logic from controller logic
 *  - Allows consistent formatting of API error responses
 *  - Supports fine-grained mapping between domain errors and HTTP status codes
 * 
 */
@RestControllerAdvice
public class MatingExceptionHandler 
{

   /**
    * Handles any {@link MatingException} thrown during the mating process and returns
    * a structured HTTP response containing:
    *
    *  - A timestamp (for diagnostics and logs)
    *  - A machine-readable error code (useful for clients)
    *  - A human-readable message
    * 
    * The response status is derived from the exception's cause using mapCauseToStatus(Cause).
    *
    * @param ex the exception thrown by domain logic when mating fails
    * @return a structured and meaningful HTTP response
    */
    @ExceptionHandler(MatingException.class)
    public ResponseEntity<Map<String, Object>> handleMatingException(MatingException ex) 
    {
        HttpStatus status = mapCauseToStatus(ex.getCauseEnum());

        return ResponseEntity.status(status).body(Map.of(
            "timestamp", Instant.now().toString(),
            "code"     , ex.getCauseEnum().getCode(),
            "message"  , ex.getMessage()
        ));
    }


    /**
     * Maps the internal domain-level Cause enum to an appropriate HTTP status code.
     * This encapsulates the logic for translating between business errors and REST semantics.
     *
     * Rationale behind mapping:
     *   - 422 (Unprocessable Entity): The request was syntactically correct but semantically invalid
     *   - 404 (Not Found): One of the parent pets wasn't found
     *   - 500 (Internal Server Error): An unknown or unexpected issue occurred
     *
     * @param cause the specific domain cause of the mating failure
     * @return an appropriate {@link HttpStatus} to send back to the client
     */
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
