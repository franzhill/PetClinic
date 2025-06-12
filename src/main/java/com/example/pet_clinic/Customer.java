package com.example.petClinic;
import jakarta.persistence.*;
import java.util.List;
@Entity
public class Customer {
    @Id @GeneratedValue private Long id;
    private String name;
    @ManyToOne
    private PetClinic petClinic;
    @OneToMany(mappedBy = "customer")
    private List<Pet> pets;
    // Getters and setters
}
