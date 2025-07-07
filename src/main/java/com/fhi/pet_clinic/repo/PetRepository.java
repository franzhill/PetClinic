package com.fhi.pet_clinic.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fhi.pet_clinic.model.Pet;

public interface PetRepository extends JpaRepository<Pet, Long> 
{}
