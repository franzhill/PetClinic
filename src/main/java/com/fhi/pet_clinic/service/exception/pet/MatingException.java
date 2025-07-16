package com.fhi.pet_clinic.service.exception.pet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.fhi.pet_clinic.model.FertilityAgeWindow;
import com.fhi.pet_clinic.model.Pet;

/**
 * Exception thrown when mating between two pets fails due to a domain rule violation.
 *
 * <p>Use static factory methods to build a meaningful {@code MatingException}
 * with a specific cause enum and a descriptive message.</p>
 *
 * <p>Examples of causes include incompatible species, sterile parent, or excessive genetic degeneracy.</p>
 */


// Spring uses this resolution order when handling exceptions:
// - Check for a @ExceptionHandler in a @RestControllerAdvice  <= TODO, later
// - If none found, check if the exception class is annotated with @ResponseStatus
// - If not, fall back to default 500 Internal Server Error
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class MatingException extends RuntimeException 
{
    /**
     * Enum representing the specific reason why mating failed.
     */
   public enum Cause 
   {
      SPECIES_MISMATCH        ("Cannot mate pets of different species: %s vs %s"),
      STERILE_PARENT          ("Pet '%s' is sterile and cannot reproduce"),
      OUTSIDE_FERTILITY_WINDOW("Pet '%s' (age %d) is outside the fertility window (%s)"),
      PARENT_NOT_FOUND        ("No pet found with ID: %d"),
      INSUFFICIENT_MATURITY   ("Pet '%s' is not mature enough for mating"),
      EXCESSIVE_DEGENERACY    ("Genetic degeneracy score %.2f is too high for mating"),
      SAME_SEX                ("Invalid sexes for mating: %s and %s"),
      UNKNOWN                 ("Unknown mating error");

      private final String messageTemplate;

      Cause(String messageTemplate) 
      {  this.messageTemplate = messageTemplate;
      }

      public String format(Object... args) 
      {  return String.format(messageTemplate, args);
      }

      public String getMessageTemplate() 
      {  return messageTemplate;
      }

      public String getCode() 
      {   return this.name(); // Or lowercase or snake_case etc.
      }
   }

   private final Cause causeEnum;

   /**
    * Creates a new MatingException with a specific cause and message.
    *
    * @param causeEnum a semantic reason from the {@code Cause} enum
    * @param message a human-readable description of the failure
    */
   public MatingException(Cause causeEnum, String message) 
   {  this(causeEnum, message, null);
   }

    /**
     * Creates a new MatingException with a cause enum, message, and underlying exception.
     *
     * @param causeEnum a semantic reason from the {@code Cause} enum
     * @param message a human-readable description
     * @param cause the original exception that triggered this one
     */
    public MatingException(Cause causeEnum, String message, Throwable cause) 
    {   super(message, cause);
        this.causeEnum = causeEnum;
    }

    /**
     * Returns the domain-specific reason for the mating failure.
     */
    public Cause getCauseEnum() 
    {   return causeEnum;
    }


   /**
    * Returns a human-readable string representation of this exception,
    * including both the message of this exception and, if present, the
    * message of its cause.
    *
    * <p>The format is:
    * <pre>
    * MatingException: Main error message | Caused by: CauseClass: Cause message
    * </pre>
    * 
    * <p>If there is no underlying cause, only the main message is printed.</p>
    *
    * @return a string describing this exception and its cause (if any)
    */
   @Override
   public String toString() 
   {
      String errMsg = String.format("%s: %s", this.getClass().getSimpleName(), this.getMessage());
      
      // Add cause message if exists
      Throwable cause = getCause();
      if (     cause != null && cause.getMessage() != null 
            && !cause.getMessage().isBlank()) 
      {  errMsg += String.format(" | Caused by: %s: %s", cause.getClass().getSimpleName(), cause.getMessage());
      }
      return errMsg;
   }



    // -----------------------------------------
    // Static factory methods 
    // -----------------------------------------

   /**
    * @param cause pass null if no Throwable cause.
    */
   public static MatingException speciesMismatch(Pet mother, Pet father, Throwable cause) 
   {  return new MatingException(Cause.SPECIES_MISMATCH,
                                 Cause.SPECIES_MISMATCH.format(mother.getSpecies(), father.getSpecies()),
                                 cause);
   }


    /**
     * @param cause pass null if no Throwable cause.
     */
    public static MatingException sterileParent(Pet pet, Throwable cause) 
    {   return new MatingException(Cause.STERILE_PARENT,
                                   Cause.STERILE_PARENT.format(pet.getName()),
                                   cause);
    }

    /**
     * @param cause pass null if no Throwable cause.
     */
    public static MatingException outsideFertilityWindow(Pet pet, Throwable cause) 
    {   return new MatingException(Cause.OUTSIDE_FERTILITY_WINDOW,
                                   Cause.OUTSIDE_FERTILITY_WINDOW.format(pet.getName(), pet.getAgeInYears(), pet.getSpecies().getFertilityAgeWindow()),
                                   cause);
    }

    /**
     * @param cause pass null if no Throwable cause.
     */
    public static MatingException insufficientMaturity(Pet pet, Throwable cause) 
    {   return new MatingException(Cause.INSUFFICIENT_MATURITY,
                                   Cause.INSUFFICIENT_MATURITY.format(pet.getName()),
                                   cause);
    }

    /**
     * @param cause pass null if no Throwable cause.
     */
    public static MatingException sameSex(Pet mother, Pet father, Throwable cause) 
    {   return new MatingException(Cause.SAME_SEX,
                                   Cause.SAME_SEX.format(mother.getSex(), father.getSex()),
                                   cause);
    }

    /**
     * Thrown when the genetic degeneracy score is too high for mating.
     *
     * @param cause pass null if no Throwable cause.
     */
    public static MatingException excessiveDegeneracy(double score, Throwable cause) 
    {   return new MatingException(Cause.EXCESSIVE_DEGENERACY,
                                   Cause.EXCESSIVE_DEGENERACY.format(score),
                                   cause);
    }

    /**
     * Thrown when one of the parent pets could not be found by ID.
     * 
     * @param cause pass null if no Throwable cause.
     */
    public static MatingException parentNotFound(Long id, Throwable cause) 
    {   return new MatingException(Cause.PARENT_NOT_FOUND,
                                   Cause.PARENT_NOT_FOUND.format(id),
                                   cause);
    }

    /**
     * General-purpose wrapper for unexpected or unknown mating failures.
     * 
     * @param causeEnum the semantic cause enum (can be null)
     * @param message custom error message
     * @param ex the original exception to wrap (pass null if none)
     */
    public static MatingException unknown(String message, Throwable cause) 
    {   return new MatingException(Cause.UNKNOWN,
                                   message != null ? message : Cause.UNKNOWN.format(),
                                   cause);
    }

}