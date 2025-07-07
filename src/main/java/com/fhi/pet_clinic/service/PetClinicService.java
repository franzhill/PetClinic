package com.fhi.pet_clinic.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fhi.pet_clinic.model.Customer;
import com.fhi.pet_clinic.model.Pet;
import com.fhi.pet_clinic.model.PetClinic;
import com.fhi.pet_clinic.repo.PetClinicRepository;
import com.fhi.pet_clinic.util.DataLoader;

import java.util.List;

@Service
public class PetClinicService 
{
    // Not using @Autowired, using constructor injection instead.
    private final PetClinicRepository clinicRepository;
    private DataLoader dataLoader;

    public PetClinicService(PetClinicRepository clinicRepository, 
                            DataLoader dataLoader) 
    {   this.clinicRepository = clinicRepository;
        this.dataLoader       = dataLoader;
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

        Customer customer1 = dataLoader.getCustomer(0);
        Customer customer2 = dataLoader.getCustomer(1);
        Pet      pet1 = dataLoader.getPet(0,0);

        petClinic.setCustomers(List.of(customer1, customer2)); // Setting inverse side
        customer1.setPets(List.of(pet1));                      // Setting inverse side

        // Now we should be OK, all the objects and their relationships (FK in DB) should be persisted:
        // We don't need to flush, as we are i a @Transaction, this will be done automatically.
        clinicRepository.save(petClinic);



    }
}
