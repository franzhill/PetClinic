package com.fhi.pet_clinic.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fhi.pet_clinic.model.FertilityAgeWindow;
import com.fhi.pet_clinic.model.Pet;
import com.fhi.pet_clinic.model.Sex;
import com.fhi.pet_clinic.model.Species;
import com.fhi.pet_clinic.repo.PetRepository;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Slf4j
public class PetService 
{
   @Autowired
   private PetRepository petRepository;
   
   private final Random random = new Random();

   /**
    * Attempts to mate two pets and returns the resulting offspring.
    * @throws IllegalStateException if mating is not possible.
    */
   public List<Pet> mate(Long motherId, Long fatherId) 
   {  log.debug("");
      Pet mother = petRepository.findById(motherId)
                                .orElseThrow(() -> new IllegalArgumentException("Mother not found"));
      Pet father = petRepository.findById(fatherId)
                                .orElseThrow(() -> new IllegalArgumentException("Father not found"));
      return mate(mother, father);  // delegate to internal logic
   }


   /**
    * Attempts to mate two pets and returns the resulting offspring.
   * @throws IllegalStateException if mating is not possible.
   */
   private List<Pet> mate(Pet parent1, Pet parent2) 
   {  log.debug("");

      validateParents(parent1, parent2);
      log.debug("parents validated");

      Species species = parent1.getSpecies(); // both species are assumed equal and validated

      int litterSize = randomizeLitterSize(species.getAvgLitterSize());
      log.debug("litterSize = {}", litterSize);

      List<Pet> offspring = new ArrayList<>();

      for (int i = 1; i <= litterSize; i++) 
      {
         Pet baby = new Pet();
         baby.setBirthDate(LocalDate.now()); // TODO add incubation period
         baby.setSpecies(species);
         baby.setMother(parent1.isFemale() ? parent1 : parent2);
         baby.setFather(parent1.isMale() ? parent1 : parent2);

         // Name: combination of parents' names with index
         String baseName = deriveBaseName(parent1, parent2);
         baby.setName(baseName + "-" + i);

         // Traits: randomize within reason
         baby.setCoatColor(randomCoatColorLike(parent1, parent2));
         baby.setEyeColor(randomEyeColorLike(parent1, parent2));
         baby.setSex(random.nextBoolean() ? Sex.MALE : Sex.FEMALE);

         // Degeneracy increases if parents are old
         int degeneracy = calculateDegeneracy(parent1, parent2);
         baby.setDegeneracyScore(degeneracy);

         // Sterility chance increases with degeneracy
         baby.setSterile(probablySterile(degeneracy));

         offspring.add(baby);
      }

      return offspring;
   }


    private void validateParents(Pet p1, Pet p2) 
    {   log.debug("");
        if (!p1.getSpecies().equals(p2.getSpecies())) 
        {   log.debug("Interspecies mating is not allowed (yet)");
            throw new IllegalStateException("Interspecies mating is not allowed (yet)");
        }

        if (Boolean.TRUE.equals(p1.getSterile()) || Boolean.TRUE.equals(p2.getSterile())) 
        {   log.debug("One of the parents is sterile");
            throw new IllegalStateException("One of the parents is sterile");
        }

        if (!p1.isFertile() || !p2.isFertile()) 
        {   log.debug("One of the parents is not within fertility window");
            throw new IllegalStateException("One of the parents is not within fertility window");
        }
    }




    private int randomizeLitterSize(int average) 
    {   return Math.max(1, average + random.nextInt(3) - 1); // average ±1
    }


    private String deriveBaseName(Pet p1, Pet p2) 
    {   return p1.getName().substring(0, 1) + p2.getName().substring(0, 1) + "-cub";
    }


    private String randomCoatColorLike(Pet p1, Pet p2) 
    {   return random.nextBoolean() ? p1.getCoatColor() : p2.getCoatColor();
    }

    private String randomEyeColorLike(Pet p1, Pet p2) 
    {   return random.nextBoolean() ? p1.getEyeColor() : p2.getEyeColor();
    }


   /**
    * Computes the degeneracy score of an offspring based on how close
    * each parent is to the end of their fertility window.
    *
    * - Score ranges from ~0 (very fertile age) to ~50 (end of window).
    * - Smooth transition based on parent's position in the fertility window.
    * - Adds a small random jitter (0–4) for biological variation.
    * - Maximum output is capped at 50.
    * 
    * @param p1    Parent 1
    * @param p2    Parent 2
    * @return a degeneracy score from 0 to 100.
    */
   private int calculateDegeneracy(Pet p1, Pet p2) {
      int deg1 = smoothDegeneracyScore(p1);
      int deg2 = smoothDegeneracyScore(p2);

      int avg = (deg1 + deg2) / 2 + random.nextInt(5); // add 0–4
      return Math.min(50, avg);
   }

   /**
    * Calculates a smooth degeneracy score for a single parent.
    * 0 → start of window
    * 50 → end of window
    */
   private int smoothDegeneracyScore(Pet pet) 
   {
      int age = pet.getAgeInYears();  // assumed to be up to date
      FertilityAgeWindow window = pet.getSpecies().getFertilityAgeWindow();

      int start = window.getFrom();
      int end = window.getTo();
      int range = end - start;

      if (range <= 0 || age < start) {
         return 50; // invalid window or not fertile → max risk
      }

      if (age >= end) {
         return 50; // beyond fertility window → max risk (should not be fertile anyway)
      }

      float ratio = (float)(age - start) / range; // 0 at start, 1 at end
      return Math.round(ratio * 50); // smooth transition from 0 → 50
   }



    private boolean probablySterile(int degeneracy) 
    {
        int threshold = 70;
        if (degeneracy < threshold) return false;
        int chance = degeneracy - threshold;
        return random.nextInt(100) < chance;
    }
}
