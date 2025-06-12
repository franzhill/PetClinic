package com.example.petClinic;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ClinicRepository extends JpaRepository<PetClinic, Long> {}
