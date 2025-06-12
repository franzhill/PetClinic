package com.example.petClinic;
import jakarta.persistence.*;
@Entity
public class Pet {
    @Id @GeneratedValue private Long id;
    private String name;
    @ManyToOne
    private Customer customer;
    // Getters and setters
}
