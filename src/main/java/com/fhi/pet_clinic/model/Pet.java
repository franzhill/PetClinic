package com.fhi.pet_clinic.model;
import java.time.LocalDate;
import java.time.Period;

import jakarta.annotation.Nullable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;


@Entity
@Setter
@Getter
public class Pet 
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank                      // Prevent null or empty name
    @Size(max = 50)
    private String name;

    @NotNull
    @ManyToOne                       // owning side
    @JoinColumn(name = "owner_id")   // FK in table employee. Can be omitted. If so, JPA auto-generates the foreign key column name using a naming convention.
    private Owner owner;

    /**
     * LocalDate is the "modern" Java type to use for dates.
     */
    @Nullable // might not be known
    private LocalDate birthDate;

    @NotNull
    @Enumerated(EnumType.STRING)
    private Sex sex;

    @NotNull
    @ManyToOne(optional = false)
    private Species species;

    // --- Parentage (forward links) ---

    @Nullable // might not be known
    @ManyToOne
    @JoinColumn(name = "mother_id")
    private Pet mother;

    @Nullable // might not be known
    @ManyToOne
    @JoinColumn(name = "father_id")
    private Pet father;

    // --- Genetic traits ---

    /** 
     * Higher means probable sterility, reduced health & lifespan
     */
    @Nullable // might not be known
    @Min(0)
    @Max(100)
    private Integer degeneracyScore = 0;

    @Nullable // might not be known
    private Boolean sterile = false;


    private String coatColor;

    private String eyeColor;



    public boolean isAgeUnknown()
    {   return birthDate == null;
    }

    public int getAgeInYears() 
    {   // TODO handle the case where age of Pet is unknown
        // For the time being just assume he's at half expectancy of his Species avg lifespan

        if (isAgeUnknown())
        {   return this.getSpecies() != null ? this.getSpecies().getExpectedLifespan() / 2 : 1;
        }
        
        return Period.between(birthDate, LocalDate.now()).getYears();
    }


    /**
     * Pet is fertile if its age falls within its fertility age window.
     */
    public boolean isFertile() 
    {   int age = this.getAgeInYears();
        var window = this.getSpecies().getFertilityAgeWindow();
        return    age >= window.getFrom() 
               && age <= window.getTo()
               && ! Boolean.FALSE.equals(this.getSterile());  // assume possible fertility if sterility is not known
    }


    public boolean isMale() {
        return sex == Sex.MALE;
    }

    public boolean isFemale() {
        return sex == Sex.FEMALE;
    }





    // --- Optional: convenience methods ---

    public void linkAsMotherOf(Pet child) {
        child.setMother(this);
    }

    public void linkAsFatherOf(Pet child) {
        child.setFather(this);
    }


}

