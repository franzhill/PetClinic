package com.fhi.pet_clinic.model;

import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Setter;
import lombok.Getter;


@Entity
@Getter
@Setter
public class Species 
{

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * e.g. "Dog", "Cat", "Dragon", "Hybrid"
     */
    @NotNull
    private String name;

    /**
     * Fertility window in years during which a pet of this species can reproduce
     */
    @NotNull
    @Embedded  // Hibernate inlines the fields of FertilityAgeWindow into the species table.
               // No join, no foreign key — it's composition, not association.
               // => species table will have columns "from" and "to"
    private FertilityAgeWindow fertilityAgeWindow;

    /**
     * In years.
     */
    @NotNull
    @Min(1)
    @Max(200)
    private int expectedLifespan;

    @NotNull
    private boolean hybridAllowed = false;

    @NotNull
    private int avgLitterSize;



    // Optional: trait constraints, color palette...
    // TODO 


}

