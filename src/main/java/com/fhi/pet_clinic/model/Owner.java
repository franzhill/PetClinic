package com.fhi.pet_clinic.model;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fhi.pet_clinic.dto.OwnerDto;

import java.util.List;

@Entity
@Setter
@Getter


// Because we have the "_comment" attribute in the owners.json
// TODO : is this good?
//@JsonIgnoreProperties(ignoreUnknown = true)
public class Owner
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @ManyToOne
    private PetClinic petClinic;

    @OneToMany(mappedBy = "owner", cascade = CascadeType.ALL)  // inverse side
    // See comments in Pet.
    //# @JsonManagedReference   // "Official" Jackson way to handle parent-child relationships
    //#                         // for inverse side.
    //#                         // (prevents infinite printing due to circular reference)
    //# @JsonBackReference  // This is the "back-link" side of the relationship.
    //#                     // Behavior: The property is NOT serialized (it is omitted from the JSON output).
    //# @JsonIgnoreProperties
    private List<Pet> pets;





    public OwnerDto mapToDto() 
    {
        OwnerDto dto = new OwnerDto();
        dto.setId  (this.getId());
        dto.setName(this.getName());
        return dto;
    }
}
