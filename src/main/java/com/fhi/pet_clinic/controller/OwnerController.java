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

import com.fhi.pet_clinic.dto.OwnerDto;
import com.fhi.pet_clinic.dto.PetDto;
import com.fhi.pet_clinic.service.OwnerService;


@RestController
@RequestMapping("/owners")
@RequiredArgsConstructor
public class OwnerController 
{
    private final OwnerService ownerService;

    @GetMapping("/{id}")
    public ResponseEntity<OwnerDto> getOwnerById(@PathVariable Long id) {
        return ResponseEntity.ok(ownerService.getOwnerById(id));
    }

    @PostMapping
    public ResponseEntity<OwnerDto> createOwner(@RequestBody OwnerDto ownerDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ownerService.createOwner(ownerDto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<OwnerDto> updateOwner(@PathVariable Long id, @RequestBody OwnerDto ownerDto) {
        return ResponseEntity.ok(ownerService.updateOwner(id, ownerDto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOwner(@PathVariable Long id) {
        ownerService.deleteOwner(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{ownerId}/pets")
    public ResponseEntity<List<PetDto>> getPetsForOwner(@PathVariable Long ownerId) {
        return ResponseEntity.ok(ownerService.getPetsForOwner(ownerId));
    }
}
