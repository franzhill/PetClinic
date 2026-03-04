package com.fhi.pet_clinic.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fhi.pet_clinic.model.Pet;

public interface PetRepository extends JpaRepository<Pet, Long> 
{

   Optional<Pet> findByOwnerId(Long ownerId);
}
