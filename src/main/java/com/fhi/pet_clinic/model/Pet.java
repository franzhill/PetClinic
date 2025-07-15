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
     * The higher the degeneracy the higher the sterility
     */
    @Min(0)
    @Max(100)
    private int degeneracyScore = 0;

    private boolean sterile = false;

    /**
     * In years
     */
    @Min(1)
    @Max(200)
    private int expectedLifespan = 14;

    private String coatColor;

    private String eyeColor;

    private boolean hasWings;



    // --- Derived logic ---

    public int getAgeInYears() {
        return Period.between(birthDate, LocalDate.now()).getYears();
    }

    public boolean isMature() {
        return getAgeInYears() >= species.getMaturityAge();
    }

    public boolean isMale() {
        return sex == Sex.MALE;
    }

    public boolean isFemale() {
        return sex == Sex.FEMALE;
    }

    public boolean isAlive(LocalDate currentDate) {
        return Period.between(birthDate, currentDate).getYears() < expectedLifespan;
    }

    // --- Optional: convenience methods ---

    public void linkAsMotherOf(Pet child) {
        child.setMother(this);
    }

    public void linkAsFatherOf(Pet child) {
        child.setFather(this);
    }


}

