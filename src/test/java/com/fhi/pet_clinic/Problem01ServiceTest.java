package com.fhi.pet_clinic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.fhi.pet_clinic.repo.PetClinicRepository;
import com.fhi.pet_clinic.service.Problem01Service;
import com.fhi.pet_clinic.util.DataLoader;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class Problem01ServiceTest 
{
    @Autowired
    EntityManager entityManager;

    @Autowired 
    private Problem01Service prb01service;
    
    @Autowired 
    private PetClinicRepository clinicRepository;

    @Autowired 
    private DataLoader dataLoader;

    @Test
    @Transactional  // This keeps the Hibernate session open during assertions
                    // and lets us access lazy loaded elements like owners
                    // via clinic.getOwners()
    void testCreateClinic() 
    {
        // GIVEN

        // WHEN I create a clinic
        prb01service.createClinic();

        // THEN it is persisted 
        // i.e. when I retrieve from the DB...

        // (Let's flush and clear the persistence context to simulate a fresh read
        //  Otherwise Hibernate might just read from cache and not from DB when
        //  we ask it to retrieve Owners and Pets.)
        entityManager.flush();
        entityManager.clear();

        var petClinic = clinicRepository.findAll().get(0);
        
        // ...it is as I created it:
        assertEquals("Happy Paws PetClinic", petClinic.getName());

        assertEquals(dataLoader.getOwner(0).getName(), 
                     petClinic.getOwners().get(0).getName());

        assertEquals(dataLoader.getPet(0, 0).getName(), 
                     petClinic.getOwners().get(0).getPets().get(0).getName());

        assertEquals(dataLoader.getOwner(1).getName(), 
                     petClinic.getOwners().get(1).getName());
    }
}
