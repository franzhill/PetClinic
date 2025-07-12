package com.fhi.pet_clinic.dto;

import lombok.Data;
import java.util.List;

@Data
public class OwnerDto {

    private Long id;
    private String name;
    private String address;
    private String phone;

    // Optional: include pet IDs or even full PetDtos if you want nested output
    private List<PetDto> pets;
}