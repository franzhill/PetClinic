package com.fhi.pet_clinic.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fhi.pet_clinic.model.PetClinic;

public interface PetClinicRepository extends JpaRepository<PetClinic, Long> 
{}
