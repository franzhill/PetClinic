package com.fhi.pet_clinic;
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

    @ManyToOne
    private Customer customer;
}
