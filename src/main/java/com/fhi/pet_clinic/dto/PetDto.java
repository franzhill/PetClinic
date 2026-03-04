package com.fhi.pet_clinic.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class PetDto {

    private Long id;
    private String name;
    private String type;         // e.g. "Dog", "Cat", etc.
    private LocalDate birthDate;

    private Long ownerId;        // optional: useful for linking

    public void setSex(Object object) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'setSex'");
    }

    public void setSpeciesName(String name2) {
      // TODO Auto-generated method stub
      throw new UnsupportedOperationException("Unimplemented method 'setSpeciesName'");
    }
}