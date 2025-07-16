package com.fhi.pet_clinic.controller;

import com.fhi.pet_clinic.model.Pet;
import com.fhi.pet_clinic.service.PetService;

import java.util.List;

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
