package com.fhi.pet_clinic.model;

import org.hibernate.annotations.NaturalId;
import org.hibernate.annotations.NaturalIdCache;

import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Setter;
import lombok.Getter;


@Entity

// Enables second-level caching for lookups by natural ID (e.g. `name`),
// allowing Hibernate to optimize repeated queries like `findByName("Dog")`.
// Recommended when `@NaturalId` is frequently queried and seldom updated.
// Requires second-level caching to be enabled globally in Hibernate settings.
@NaturalIdCache
@Getter
@Setter
public class Species 
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * A functional business key used for stable lookup and identity across environments.
     *
     * This field is not the database primary key, but is considered "naturally unique" in the domain,
     * and can be used to reference this entity meaningfully (e.g. "Dog", "Cat").
     *
     * Using @NaturalId allows Hibernate to optimize queries and caching when looking up by this field,
     * while preserving a separate surrogate @Id for joins and foreign keys.
     * 
     * e.g. "Dog", "Cat", "Dragon", "Hybrid"
     */
    @NotNull
    @NaturalId
    @Column(nullable = false, unique = true)
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



   @Override
   public String toString() 
   {   return name;
   }


}

