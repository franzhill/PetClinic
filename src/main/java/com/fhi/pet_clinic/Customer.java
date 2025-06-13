package com.fhi.pet_clinic;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Entity
@Setter
@Getter
public class Customer 
{
    @Id @GeneratedValue private Long id;
    private String name;

    @ManyToOne
    private PetClinic petClinic;

    @OneToMany(mappedBy = "customer")
    private List<Pet> pets;
}
