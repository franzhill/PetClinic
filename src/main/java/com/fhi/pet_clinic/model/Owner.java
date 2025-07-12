package com.fhi.pet_clinic.model;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@Entity
@Setter
@Getter


// Because we have the "_comment" attribute in the owners.json
// TODO : is this good?
@JsonIgnoreProperties(ignoreUnknown = true)
public class Owner
{
    @Id @GeneratedValue 
    private Long id;

    private String name;

    @ManyToOne
    private PetClinic petClinic;

    @OneToMany(mappedBy = "owner", cascade = CascadeType.ALL)  // inverse side
    private List<Pet> pets;
}
