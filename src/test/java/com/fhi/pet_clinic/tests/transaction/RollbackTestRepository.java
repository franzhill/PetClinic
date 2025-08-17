package com.fhi.pet_clinic.tests.transaction;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repostiory class used in TransactioRollbackTest.java
 */
public interface RollbackTestRepository extends JpaRepository<RollbackTestEntity, Long> 
{}
