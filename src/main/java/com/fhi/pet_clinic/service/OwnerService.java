package com.fhi.pet_clinic.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.fhi.pet_clinic.dto.OwnerDto;
import com.fhi.pet_clinic.dto.PetDto;
import com.fhi.pet_clinic.repo.OwnerRepository;
import com.fhi.pet_clinic.repo.PetRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OwnerService
{
    // Inject your repository here
    private final OwnerRepository ownerRepository;
    private final PetRepository petRepository;


    public OwnerDto getOwnerById(Long id) 
    {
        // Dummy example – implement real logic
        return new OwnerDto();
    }


    public OwnerDto createOwner(OwnerDto ownerDto) {
        return new OwnerDto();
    }


    public OwnerDto updateOwner(Long id, OwnerDto ownerDto) {
        return new OwnerDto();
    }


    public void deleteOwner(Long id) {
        // no-op
    }


    public List<PetDto> getPetsForOwner(Long ownerId) {
        return List.of();
    }
}
