package com.fhi.pet_clinic.model;
import java.time.LocalDate;
import java.time.Period;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fhi.pet_clinic.dto.PetDto;

import jakarta.annotation.Nullable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import net.minidev.json.annotate.JsonIgnore;


@Entity
@Setter
@Getter
public class Pet 
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank                      // Prevent null or empty name.
    @Size(max = 50)
    private String name;

    @NotNull
    @ManyToOne                       // Owning side.
    @JoinColumn(name = "owner_id")   // Indicates the corresponding FK in table 'pet'. Can
                                     // be omitted. If so, JPA auto-generates the FK column 
                                     // name using a naming convention.

    // We're trying to get the owner id printed in JSON serializations of Pet.
    // None of the below work in a satasfactory manner.
    // Defining
    //   getOwnerId()
    // messes up with the JPA Repository Derived Query Methods.
    // Using  @JsonManagedReference/@JsonBackReference hides the owner completely,
    // or, if switched, seems to confuse the mapper resulting in HTTP status 415 when calling
    // a constructor with a serialized Pet as body.
    // So in the end we're using @JsonIgnoreProperties.

    //#@JsonBackReference      // "Official" Jackson way to handle parent-child relationships
    //#                        // for owning side.
    //#                        // (prevents infinite printing due to circular reference)
    //#                        // This means this attribute won't be serialized by Jackson.
    //#@JsonManagedReference  // "owning" side of the relationship (from the perspective of serialization).
    //#                       // The property is serialized normally.
    @JsonIgnoreProperties("pets") // <--- Stops the loop back to the list
    private Owner owner;

    //# // Jackson interprets this getter as a JSON property named 'ownerId'
    //# // (and thus serializes it). This allows us to still "see" the owner 
    //# // despite it being hidden by @JsonBackReference.
    //# @Transient   // Tells JPA/Hibernate to ignore that field since we only need it 
    //#              // for "JSON" purposes.
    //# public Long getOwnerId() 
    //# {   return this.owner != null ? this.owner.getId() : null;
    //# }

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
               && age <= window.getTo();
               //# && ! Boolean.FALSE.equals(this.getSterile());  // assume possible fertility if sterility is not known
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




    /*
        Here we have a classic debate in the Spring community: the Mapper Placement.
        - Solution A: The "Active Record" Style
            Have mapToDto() directly inside the Owner entity.
            Pros: It’s very convenient and keeps the logic close to the data. 
                It feels like the "Active Record" pattern.
            Cons: It creates a circular dependency (Entity knows about DTO, DTO usually 
                describes Entity). Technically, entities are part of your "Domain" layer
                and DTOs are part of your "API" layer. Purists would argue the Entity 
                shouldn't even know the API exists.
        - Solution B: Dedicated DTO layer, use of Mappers (dedicated Mapper class or MapStruct ...)
        - Verdict: For a small project like PetClinic, it’s perfectly fine to go with solution A.
                However, if things grows, solution B might be cleaner.
     */
    public PetDto mapToDto() 
    {
        PetDto dto = new PetDto();
        dto.setId(this.getId());
        dto.setName(this.getName());
        dto.setSex(this.getSex() != null ? this.getSex().toString() : null);
        dto.setBirthDate(this.getBirthDate());
        if (this.getSpecies() != null) {
            dto.setSpeciesName(this.getSpecies().getName());
        }
        if (this.owner != null)
        {   dto.setOwnerId(this.owner.getId());
        }
        return dto;
    }

}

