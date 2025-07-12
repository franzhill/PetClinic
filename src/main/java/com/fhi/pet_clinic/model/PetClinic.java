package com.fhi.pet_clinic.model;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Entity
@Setter
@Getter
public class PetClinic 
{
    @Id @GeneratedValue 
    private Long id;

    private String name;

    @OneToMany(mappedBy = "petClinic")  // inverse side
    private List<Owner> owners;
}
