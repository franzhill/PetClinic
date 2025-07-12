package com.fhi.pet_clinic.model;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Entity
@Setter
@Getter
public class Owner
{
    @Id @GeneratedValue private Long id;
    private String name;

    @ManyToOne
    private PetClinic petClinic;

    @OneToMany(mappedBy = "owner")  // inverse side
    private List<Pet> pets;
}
