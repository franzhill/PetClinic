package com.fhi.pet_clinic.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.fhi.pet_clinic.model.Owner;
import com.fhi.pet_clinic.model.Pet;
import com.fhi.pet_clinic.repo.OwnerRepository;
import com.fhi.pet_clinic.repo.PetRepository;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OwnerService {

    private final OwnerRepository ownerRepository;
    private final PetRepository petRepository;


    public Owner getOwnerById(Long id) {
        return ownerRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Owner not found with id: " + id));
    }

    public Owner createOwner(Owner ownerDto) 
    {
        Owner owner = new Owner();
        owner.setName(ownerDto.getName());
        return ownerRepository.save(owner);
    }

    public Owner updateOwner(Long id, Owner ownerDto) 
    {
        Owner owner = ownerRepository.findById(id)
                     .orElseThrow(() -> new EntityNotFoundException("Owner not found with id: " + id));
        
        owner.setName(ownerDto.getName());
        return ownerRepository.save(owner);
    }


    public void deleteOwner(Long id) 
    {
        if (!ownerRepository.existsById(id)) {
            throw new EntityNotFoundException("Owner not found with id: " + id);
        }

        ownerRepository.deleteById(id);


    }


    public List<Pet> getPetsForOwner(Long ownerId) {
        // We look up the owner first to ensure they exist
        if (!ownerRepository.existsById(ownerId)) {
            throw new EntityNotFoundException("Owner not found with id: " + ownerId);
        }
        
        // Assuming your PetRepository has a findByOwnerId method
        return petRepository.findByOwnerId(ownerId).stream()
                .toList();
    }


}