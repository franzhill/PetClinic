package com.fhi.pet_clinic;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class PetClinicService 
{
    private final PetClinicRepository clinicRepository;

    public PetClinicService(PetClinicRepository clinicRepository) 
    {   this.clinicRepository = clinicRepository;
    }

    @Transactional
    public void createClinic() 
    {
        // Let's create a pet clinic with a customer and his pet
        // and save in DB.
        // In the automated test, we'll verify that the chain of objects has 
        // been saved as intended.

        PetClinic petClinic = new PetClinic();
                  petClinic.setName("Happy Paws PetClinic");

        Customer customer1 = new Customer();
                 customer1.setName("Alice");

        Pet pet1 = new Pet();
            pet1.setName("Fluffy");


        petClinic.setCustomers(List.of(customer1));
        customer1.setPets(List.of(pet1));
        clinicRepository.save(petClinic);


        // TODO : Relationships are not properly set yet
        //         You must set the bidirectional links and fix cascading


    }
}
