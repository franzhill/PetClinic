package com.fhi.pet_clinic.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.fhi.pet_clinic.model.Customer;

public interface CustomerRepository extends JpaRepository<Customer, Long> 
{}
