package com.fhi.pet_clinic.controller;

import com.fhi.pet_clinic.model.Pet;
import com.fhi.pet_clinic.service.PetService;

import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pets")
public class PetController {

   private final PetService petService;

   // Constructor autowiring
   public PetController(PetService petService) 
   {
      this.petService = petService;
   }


    @GetMapping
    public List<Pet> getAllPets() {
        return petService.findAllPets();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Pet> getPetById(@PathVariable Long id) {
        Optional<Pet> pet = petService.findPetById(id);
        return pet.map(ResponseEntity::ok)
                  .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Pet> createPet(@RequestBody Pet pet) {
        Pet savedPet = petService.savePet(pet);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedPet);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Pet> updatePet(@PathVariable Long id, @RequestBody Pet petDetails) {
        try {
            Pet updatedPet = petService.updatePet(id, petDetails);
            return ResponseEntity.ok(updatedPet);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePet(@PathVariable Long id) {
        try {
            petService.deletePet(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }



   /**
    * Mates two pets together and returns the resulting offspring.
    * 
    * Example: POST /api/pets/mate?motherId=1&fatherId=2
    */
   @PostMapping("/mate")
   public ResponseEntity<List<Pet>> matePets(@RequestParam Long motherId,
                                       @RequestParam Long fatherId) 
   {
      List<Pet> offspring = petService.mate(motherId, fatherId);
      return ResponseEntity.ok(offspring);
   }
}
