package com.fhi.pet_clinic.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * A species is considered fertile if an individual's age
 * falls within this window (in years).
 */
@Embeddable
@Getter @Setter
public class FertilityAgeWindow 
{


   /**
    * Start age, in years.
    */
   @NotNull
   @Min(0) @Max(100)
   @Column(name = "fertility_from") // from and to are reserved SQL keywords,
   private int from;

   /**
    * End age, in years.
    */
   @NotNull
   @Min(0) @Max(200)
   @Column(name = "fertility_to") // from and to are reserved SQL keywords,
   private int to;

   @Override
   public String toString() 
   {   return "[" + from + "-" + to + "]";
   }

}
