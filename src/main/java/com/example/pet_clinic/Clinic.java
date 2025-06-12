package com.example.petClinic;
import jakarta.persistence.*;
import java.util.List;
@Entity
public class PetClinic {
    @Id @GeneratedValue private Long id;
    private String name;
    @OneToMany(mappedBy = "petClinic")
    private List<Customer> customers;
    // Getters and setters
}
