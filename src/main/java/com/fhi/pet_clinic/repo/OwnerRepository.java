package com.fhi.pet_clinic.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fhi.pet_clinic.model.Owner;

public interface OwnerRepository extends JpaRepository<Owner, Long> 
{}
