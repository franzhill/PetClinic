package com.fhi.pet_clinic.controller;

import lombok.RequiredArgsConstructor;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fhi.pet_clinic.model.Owner;
import com.fhi.pet_clinic.model.Pet;
import com.fhi.pet_clinic.service.OwnerService;


@RestController
@RequestMapping("/owners")
@RequiredArgsConstructor
public class OwnerController 
{
    private final OwnerService ownerService;

    @GetMapping("/{id}")
    public ResponseEntity<Owner> getOwnerById(@PathVariable Long id) {
        return ResponseEntity.ok(ownerService.getOwnerById(id)
                                            //# .mapToDto()   // for the time being we'll be returning the entity directly
                                            );
    }

    @PostMapping
    public ResponseEntity<Owner> createOwner(@RequestBody Owner owner) {
        return ResponseEntity.status(HttpStatus.CREATED)
                             .body(ownerService.createOwner(owner)
                                             //#  .mapToDto()  // for the time being we'll be returning the entity directly
                                   );
    }

    @PutMapping("/{id}")
    public ResponseEntity<Owner> updateOwner(@PathVariable Long id, @RequestBody Owner owner) {
        return ResponseEntity.ok(ownerService.updateOwner(id, owner)
                                             //# .mapToDto()   // for the time being we'll be returning the entity directly
                                );   
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOwner(@PathVariable Long id) {
        ownerService.deleteOwner(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{ownerId}/pets")
    public ResponseEntity<List<Pet>> getPetsForOwner(@PathVariable Long ownerId) {
        return ResponseEntity.ok(ownerService.getPetsForOwner(ownerId)
                                          // for the time being we'll be returning the entity directly
                                          //#   .stream()
                                          //#   .map(Pet::mapToDto)   
                                          //#   .toList()
                                 );
    }
}
