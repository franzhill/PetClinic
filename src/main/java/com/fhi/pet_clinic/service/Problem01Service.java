package com.fhi.pet_clinic.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fhi.pet_clinic.model.Owner;
import com.fhi.pet_clinic.model.Pet;
import com.fhi.pet_clinic.model.PetClinic;
import com.fhi.pet_clinic.repo.PetClinicRepository;
import com.fhi.pet_clinic.util.DataLoader;

import jakarta.persistence.OneToMany;

import java.util.List;

/*
 * This is intended for demonstrating the importance of correctly setting
 * owning/inverse sides in JPA bidirectional relationships.
 */
@Service
public class Problem01Service 
{
    // Not using @Autowired, using constructor injection instead.
    private PetClinicRepository clinicRepository;
    private DataLoader dataLoader;

    public Problem01Service(PetClinicRepository clinicRepository, 
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
        // Above we set the inverse side of the relationship => JPA won't persist the customer!
        // We need to do this:
        owner1.setPetClinic(petClinic);
        owner2.setPetClinic(petClinic);

        owner1.setPets(List.of(pet1));  // Sets the inverse side of the bidirectional relationship owner <-> pet
        // Above we set the inverse side of the relationship => JPA won't persist the pet!
        // We need to do this:
        pet1.setCustomer(owner1);

        // Also, if we want to just save the highest node in the graph (the petClinic) and have
        // all the descendant entities created in DB automatically by Hibernate we need to set 
        // cascade=CascadeType.PERSIST (at least) on the descendant entities.
        // i.e. :
        //    @OneToMany(mappedBy = "petClinic", cascade = CascadeType.PERSIST)
        //    private List<Customer> customers;
        //
        //    @OneToMany(mappedBy = "customer", cascade = CascadeType.PERSIST)
        //    private List<Pet> pets;
        //
        // Otherwise we'd have to do:
        //    clinicRepository.save(petClinic);
        //    customerRepository.save(customer1);
        //    customerRepository.save(customer2);
        //    petRepository.save(pet1);

        // Now we should be OK, all the objects and their relationships (FK in DB) should be persisted:
        // We don't need to flush, as we are in a @Transaction, this will be done automatically.
        clinicRepository.save(petClinic);
    }
}
