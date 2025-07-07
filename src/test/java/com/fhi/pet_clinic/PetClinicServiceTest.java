package com.fhi.pet_clinic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.fhi.pet_clinic.repo.PetClinicRepository;
import com.fhi.pet_clinic.service.PetClinicService;
import com.fhi.pet_clinic.util.DataLoader;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PetClinicServiceTest 
{
    @Autowired
    EntityManager entityManager;

    @Autowired 
    private PetClinicService clinicService;
    
    @Autowired 
    private PetClinicRepository clinicRepository;

    @Autowired 
    private DataLoader dataLoader;

    @Test
    @Transactional  // This keeps the Hibernate session open during assertions
                    // and lets us access lazy loaded elements like customers
                    // via clinic.getCustomers()
    void testCreateClinic() 
    {
        // GIVEN

        // WHEN I create a clinic
        clinicService.createClinic();

        // THEN it is persisted 
        // i.e. when I retrieve from the DB...

        // (Let's flush and clear the persistence context to simulate a fresh read
        //  Otherwise Hibernate might just read from cache and not from DB when
        //  we ask it to retrieve Customers and Pets.)
        entityManager.flush();
        entityManager.clear();

        var petClinic = clinicRepository.findAll().get(0);
        
        // ...it is as I created it:
        assertEquals("Happy Paws PetClinic", petClinic.getName());

        assertEquals(dataLoader.getCustomer(0).getName(), petClinic.getCustomers().get(0).getName());

        assertEquals(dataLoader.getPet(0, 0).getName(), petClinic.getCustomers().get(0).getPets().get(0).getName());

        assertEquals(dataLoader.getCustomer(1).getName(), petClinic.getCustomers().get(1).getName());
    }
}
