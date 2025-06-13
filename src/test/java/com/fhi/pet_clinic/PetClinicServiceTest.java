package com.fhi.pet_clinic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PetClinicServiceTest 
{
    @Autowired 
    private PetClinicService clinicService;
    
    @Autowired 
    private PetClinicRepository clinicRepository;

    @Test
    void testCreateClinic() 
    {
        // GIVEN

        // WHEN I create a clinic
        clinicService.createClinic();

        // THEN it is persisted 
        // i.e. when I retrieve from the DB...
        var petClinic = clinicRepository.findAll().get(0);
        
        // ...it is as I created it:
        assertEquals("Happy Paws PetClinic", petClinic.getName());
        assertNotNull(petClinic.getCustomers());
        assertFalse(petClinic.getCustomers().isEmpty());
        assertEquals("Alice", petClinic.getCustomers().get(0).getName());
        assertFalse(petClinic.getCustomers().get(0).getPets().isEmpty());
        assertEquals("Fluffy", petClinic.getCustomers().get(0).getPets().get(0).getName());
    }
}
