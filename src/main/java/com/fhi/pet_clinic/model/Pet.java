package com.fhi.pet_clinic.model;
import jakarta.persistence.*;

import lombok.Setter;
import lombok.Getter;


@Entity
@Setter
@Getter
public class Pet 
{
    @Id @GeneratedValue private Long id;
    private String name;

    @ManyToOne                          // owning side
    @JoinColumn(name = "owner_id")   // FK in table employee. Can be omitted. If so, JPA auto-generates the foreign key column name using a naming convention.
    private Owner owner;
}
