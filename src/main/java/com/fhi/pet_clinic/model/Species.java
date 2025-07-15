package com.fhi.pet_clinic.model;

import jakarta.persistence.*;

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
    private String name;

    /**
     * In years
     */
    private int maturityAge; 

    /**
     * In years
     */
    private int defaultLifespan;


    private boolean hybridAllowed = false;

    private int avgLitterSize; 

    // Optional: trait constraints, color palette...


    // --- Convenience constructor ---
    public Species() {}

    public Species(String name, int maturityAge, boolean hybridAllowed) 
    {
        this.name = name;
        this.maturityAge = maturityAge;
        this.hybridAllowed = hybridAllowed;
    }


}

