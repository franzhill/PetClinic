package com.fhi.pet_clinic.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fhi.pet_clinic.model.FertilityAgeWindow;
import com.fhi.pet_clinic.model.Pet;
import com.fhi.pet_clinic.model.Sex;
import com.fhi.pet_clinic.model.Species;
import com.fhi.pet_clinic.repo.PetRepository;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import com.fhi.pet_clinic.service.exception.pet.MatingException;

@Service
@RequiredArgsConstructor
@Slf4j
public class PetService 
{
   @Autowired
   private PetRepository petRepository;
   
   private final Random random = new Random();


   public List<Pet> findAllPets() {
      return petRepository.findAll();
   }

   public Optional<Pet> findPetById(Long id) {
      return petRepository.findById(id);
   }

   public Pet savePet(Pet pet) {
      return petRepository.save(pet);
   }

   public Pet updatePet(Long id, Pet petDetails) {
      return petRepository.findById(id)
              .map(pet -> {
                   pet.setName(petDetails.getName());
                   pet.setSpecies(petDetails.getSpecies());
                   pet.setBirthDate(petDetails.getBirthDate());
                   // Update other attributes here
                   return petRepository.save(pet);
              }).orElseThrow(() -> new IllegalArgumentException("Pet not found"));
   }

   public void deletePet(Long id) {
      if (!petRepository.existsById(id)) {
         throw new IllegalArgumentException("Pet not found");
      }
      petRepository.deleteById(id);
   }


   /**
    * Attempts to mate two pets and returns the resulting offspring.
    * @throws MatingException if mating is not possible.
    */
   public List<Pet> mate(Long motherId, Long fatherId) 
   {
      log.debug("");
      Pet mother = petRepository.findById(motherId)
                                .orElseThrow(() -> MatingException.parentNotFound(motherId, null));
      Pet father = petRepository.findById(fatherId)
                                .orElseThrow(() -> MatingException.parentNotFound(fatherId, null));
      return mate(mother, father);  // delegate to internal logic
   }


  /**
   * Attempts to mate two pets and returns the resulting offspring.
   *
   *  @throws MatingException if mating is not possible.
   */
   private List<Pet> mate(Pet mother, Pet father) 
   {  log.debug("");

      validateParents(mother, father);
      log.debug("parents validated");

      Species species = mother.getSpecies(); // both species are assumed equal and validated

      int litterSize = randomizeLitterSize(species.getAvgLitterSize());
      log.debug("litterSize = {}", litterSize);

      List<Pet> offspring = new ArrayList<>();

      for (int i = 1; i <= litterSize; i++) 
      {
         Pet baby = new Pet();
         baby.setBirthDate(LocalDate.now()); // TODO add incubation period
         baby.setSpecies(species);
         baby.setMother(mother.isFemale() ? mother : father);
         baby.setFather(mother.isMale() ? mother : father);

         // Name: combination of parents' names with index
         String baseName = deriveBaseName(mother, father);
         baby.setName(baseName + "-" + i);

         // Traits: randomize within reason
         baby.setCoatColor(randomCoatColorLike(mother, father));
         baby.setEyeColor(randomEyeColorLike(mother, father));
         baby.setSex(random.nextBoolean() ? Sex.MALE : Sex.FEMALE);

         // Degeneracy increases if parents are old
         int degeneracy = calculateDegeneracy(mother, father);
         baby.setDegeneracyScore(degeneracy);

         // Sterility chance increases with degeneracy
         baby.setSterile(probablySterile(degeneracy));

         offspring.add(baby);
      }

      return offspring;
   }


    /**
     * Validates the two parent pets to ensure they can mate.
     * 
     * <p>By examining a certain number of domain logic conditions.
     * If any condition is violated, a {@link MatingException} is thrown, 
     * providing the reason for the mating failure.</p>
     *
     * @param p1 the first parent to validate
     * @param p2 the second parent to validate
     * @throws MatingException if any validation rule is violated
     */
    private void validateParents(Pet mother, Pet father) 
    {   log.debug("");
        if (!mother.getSpecies().equals(father.getSpecies())) 
        {   log.debug("speciesMismatch");
            throw MatingException.speciesMismatch(mother, father, null);
        }

        if (Boolean.TRUE.equals(mother.getSterile()) ) 
        {   log.debug("sterileParent");
            throw MatingException.sterileParent(mother, null);
        }

        if (Boolean.TRUE.equals(father.getSterile()) ) 
        {   log.debug("sterileParent");
            throw MatingException.sterileParent(father, null);
        }
       if (!mother.isFertile()) 
       {   log.debug("outsideFertilityWindow");
           throw MatingException.outsideFertilityWindow(mother, null);
       }
       if (!father.isFertile()) 
       {   log.debug("outsideFertilityWindow");
           throw MatingException.outsideFertilityWindow(father, null);
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
    * Computes the degeneracy score of an offspring
    * 
    * @param mother    Parent 1
    * @param father    Parent 2
    * @return a degeneracy score from 0 to 100.
    */
   private int calculateDegeneracy(Pet mother, Pet father) 
   {
      return calculateSmoothDegeneracyScore(mother, father);
   }


   /**
    * Calculates a smooth degeneracy score for the offspring of two parents.
    *
    * Degeneracy is on a scale of 0 to 100.
    * Higher means probable sterility, reduced health & lifespan.
    * 
    * The score is based on:
    * - Proximity to the end of fertility window (both parents)
    * - Inbreeding level (based on ancestry up to great-grandparents)
    */
   private int calculateSmoothDegeneracyScore(Pet mother, Pet father) 
   {
    int fertilityRisk = calculateFertilityWindowRisk(mother) + 
                        calculateFertilityWindowRisk(father); // 0 to 200
    int inbreedingRisk = calculateInbreedingRisk(mother, father); // 0 to 100

    // Combine with weighted average (fertility is 0–200, so we normalize it)
    float normalizedFertility = fertilityRisk / 2f; // Bring back to 0–100
    float weightedScore = 0.4f * normalizedFertility + 0.6f * inbreedingRisk;

    // Add small randomness: ±10
    int noise = random.nextInt(20) - 10; // produces value between -10 and +10
    int finalScore = Math.round(weightedScore) + noise;

    // Clamp between 0 and 100
    return Math.max(0, Math.min(100, finalScore));
}



   /**
    * Calculates a smoothed fertility risk score based on the pet's age relative to its fertility window.
    * 
    * <p>This score reflects how close the pet is to the end of its fertile lifespan, using a non-linear
    * curve for a more biologically realistic progression. The result is an integer between 0 and 100:</p>
    * 
    * <ul>
    *   <li><b>0</b>: just entered fertility window (minimal risk)</li>
    *   <li><b>50</b>: midpoint of fertility window</li>
    *   <li><b>100</b>: at or beyond the end of the fertility window (max risk)</li>
    * </ul>
    * 
    * <p>This implementation uses a cosine interpolation function to produce a smooth, gradual increase in risk
    * as the pet ages through its fertility window. The curve accelerates gently and flattens out near the ends,
    * avoiding harsh jumps and better modeling biological aging effects.</p>
    *
    * <p>Special cases:
    * <ul>
    *   <li>If the pet's age is below or above the fertility window: returns 100</li>
    *   <li>If the fertility window is invalid (range ≤ 0): returns 100</li>
    * </ul>
    * </p>
    * 
    * @param pet the pet whose fertility risk is to be evaluated
    * @return an integer score between 0 (low fertility-related risk) and 100 (very high risk)
    */
   private int calculateFertilityWindowRisk(Pet pet) 
   {
      int age = pet.getAgeInYears();
      FertilityAgeWindow window = pet.getSpecies().getFertilityAgeWindow();

      int start = window.getFrom();
      int end = window.getTo();
      int range = end - start;

      if (range <= 0 || age < start || age > end) 
      {  return 100; // Invalid or outside fertile window → max risk
      }

      float ratio = (float)(age - start) / range; // normalized to [0, 1]
      float smooth = (1 - (float)Math.cos(Math.PI * ratio)) / 2f; // cosine-smoothed [0, 1]

      return Math.round(smooth * 100f);
   }


   /**
    * Calculates a normalized inbreeding risk score between two parent pets.
    * 
    * <p>Checks for common ancestors up to a given depth (3 generations)
    * and calculates how much of their ancestry tree overlaps. The more ancestors
    * they share, the higher the inbreeding risk score.</p>
    * 
    * <p>The result is expressed as a percentage from 0 to 100:
    * <ul>
    *   <li><b>0</b>: completely unrelated</li>
    *   <li><b>50</b>: moderate overlap or ancestry unknown</li>
    *   <li><b>100</b>: siblings or parent-child relationship</li>
    * </ul>
    * </p>
    * 
    * @param mother First parent
    * @param father Second parent
    * @return a float between 0 and 100 representing the inbreeding risk
    */
   private int calculateInbreedingRisk(Pet mother, Pet father) 
   {
      Set<Long> ancestry1 = collectAncestorIds(mother, 3);
      Set<Long> ancestry2 = collectAncestorIds(father, 3);

      if (ancestry1.isEmpty() || ancestry2.isEmpty()) {
         return 50; // Unknown ancestry: medium default risk
      }

      Set<Long> intersection = new HashSet<>(ancestry1);
      intersection.retainAll(ancestry2);

      int commonAncestors = intersection.size();
      int totalAncestors = ancestry1.size() + ancestry2.size();

      if (commonAncestors == 0) return 0;

      float similarityRatio = (float) commonAncestors / (float) (totalAncestors / 2);

      return Math.round(Math.min(100f, similarityRatio * 100f)); // Cap at 100
   }


   /**
    * Recursively collects the IDs of a pet’s ancestors up to a specified number of generations.
    * 
    * <p>This function traverses the maternal and paternal lineage and returns
    * a set of unique ancestor IDs. Used primarily for inbreeding checks.</p>
    * 
    * <p>For example, with 3 generations, the result may include:
    * <ul>
    *   <li>Parents</li>
    *   <li>Grandparents</li>
    *   <li>Great-grandparents</li>
    * </ul>
    * </p>
    * 
    * @param pet the root pet (offspring) whose ancestry will be explored
    * @param ancestorsDepth how many generations to explore recursively
    * @return a set of ancestor IDs (empty if none or null input)
    */
   private Set<Long> collectAncestorIds(Pet pet, int ancestorsDepth) 
   {
      Set<Long> ids = new HashSet<>();
      if (pet == null || ancestorsDepth <= 0) return ids;

      if (pet.getMother() != null) {
         ids.add(pet.getMother().getId());
         ids.addAll(collectAncestorIds(pet.getMother(), ancestorsDepth - 1));
      }

      if (pet.getFather() != null) {
         ids.add(pet.getFather().getId());
         ids.addAll(collectAncestorIds(pet.getFather(), ancestorsDepth - 1));
      }

      return ids;
   }



    private boolean probablySterile(int degeneracy) 
    {
        int threshold = 70;
        if (degeneracy < threshold) return false;
        int chance = degeneracy - threshold;
        return random.nextInt(100) < chance;
    }
}


