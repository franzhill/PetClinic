package com.example.petClinic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ClinicServiceTest {
    @Autowired private ClinicService clinicService;
    @Autowired private ClinicRepository clinicRepository;

    @Test
    void testCreateClinic() {
        clinicService.createClinic();
        var petClinic = clinicRepository.findAll().get(0);
        assertEquals("Happy Paws PetClinic", petClinic.getName());
        assertNotNull(petClinic.getCustomers());
        assertFalse(petClinic.getCustomers().isEmpty());
        assertFalse(petClinic.getCustomers().get(0).getPets().isEmpty());
    }
}
