package com.fhi.pet_clinic.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fhi.pet_clinic.model.Owner;
import com.fhi.pet_clinic.model.Pet;
import com.fhi.pet_clinic.model.PetClinic;
import com.fhi.pet_clinic.repo.PetClinicRepository;
import com.fhi.pet_clinic.util.DataLoader;

import java.util.List;

/*
 * This is intended for demonstrating the importance of correctly setting
 * owning/inverse sides in JPA bidirectional relationships.
 */
@Service
public class PetClinicService 
{
    // Not using @Autowired, using constructor injection instead.
    private PetClinicRepository clinicRepository;
    private DataLoader dataLoader;

    public PetClinicService(PetClinicRepository clinicRepository, 
                            DataLoader dataLoader) 
    {   this.clinicRepository = clinicRepository;
        this.dataLoader       = dataLoader;
    }

    @Transactional
    public void createClinic() 
    {
        // Let's create a pet clinic with an owner and their pet and save that in DB.
        // In the automated test, we'll verify that the chain of objects has indeed
        // been saved as intended.

        PetClinic petClinic = new PetClinic();
                  petClinic.setName("Happy Paws PetClinic");

        Owner owner1 = dataLoader.getOwner(0);
        Owner owner2 = dataLoader.getOwner(1);
        Pet      pet1      = dataLoader.getPet(0,0);

        petClinic.setOwners(List.of(owner1, owner2)); // Sets the inverse side of the bidirectional relationship clinic <-> owner
        owner1.setPets(List.of(pet1));                // Sets the inverse side of the bidirectional relationship owner <-> pet

        // Now we should be OK, all the objects and their relationships (FK in DB) should be persisted:
        // We don't need to flush, as we are in a @Transaction, this will be done automatically.
        clinicRepository.save(petClinic);
    }
}
