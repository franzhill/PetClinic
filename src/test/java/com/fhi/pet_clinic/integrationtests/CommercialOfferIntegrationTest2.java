package com.airbus.ebcs.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.airbus.ebcs.domain.entity.CommercialOffer;
import com.airbus.ebcs.domain.entity.Offer;
import com.airbus.ebcs.repository.CommercialOfferRepository;
import com.airbus.ebcs.repository.OfferRepository;

/**
 * Run with:
 *   mvn clean test -Dtest=CommercialOfferIntegrationTest2
 *
 * This test inherits:
 * - SpringBootTest + MockMvc setup
 * - FixtureEngine boot + BeforeAll/AfterAll group execution
 * - Auth wiring per request
 */
class CommercialOfferIntegrationTest2 extends ParentIntegrationTest 
{
   // Test-specific constants
   public static final long A320_PROGRAM_ID = 36L;
   public static final long A350_PROGRAM_ID = 37L;

   @Autowired private OfferRepository offerRepository;
   @Autowired private CommercialOfferRepository commercialOfferRepository;


   @Test
   void example() throws Exception 
   {
      /*
      mockMvc.perform(post("/the/endpoint")
             .with(authentication(getTestAuthentication()))   // Test AirbusUser 
             .with(csrf())                                   // for POST/PUT/PATCH/DELETE
             .contentType(MediaType.APPLICATION_JSON)
             .content("{\"name\":\"X\"}"))
             .andExpect(status().isCreated());
      */
   }


    @Test
    void checkCommercialOfferCreateOffer() throws Exception 
    {
        // WHEN: offers are created via fixtures
        long offer1Id = fx.callFixtureReturnId("create-offer-a320_1");
        long offer2Id = fx.callFixtureReturnId("create-offer-a320_2");
        long offer3Id = fx.callFixtureReturnId("create-offer-a350");

        // THEN: they are created in DB
        Optional<Offer> oOffer1 = offerRepository.findById(offer1Id);
        Optional<Offer> oOffer2 = offerRepository.findById(offer2Id);
        Optional<Offer> oOffer3 = offerRepository.findById(offer3Id);

        assertThat(oOffer1).isPresent();
        assertThat(oOffer2).isPresent();
        assertThat(oOffer3).isPresent();

        // THEN: and the offers created have associated commercial offers
        long co1Id = oOffer1.get().getCommercialOffer().getId();
        long co2Id = oOffer2.get().getCommercialOffer().getId();
        long co3Id = oOffer3.get().getCommercialOffer().getId();

        Optional<CommercialOffer> oCo1 = commercialOfferRepository.findById(co1Id);
        Optional<CommercialOffer> oCo2 = commercialOfferRepository.findById(co2Id);
        Optional<CommercialOffer> oCo3 = commercialOfferRepository.findById(co3Id);

        // THEN: offer1 and offer2 share the same commercial offer (same aircraft program)
        assertThat(co1Id).isEqualTo(co2Id);

        // THEN: commercial offers match expected aircraft programs
        assertThat(oCo1).isPresent().get()
            .extracting(co -> co.getAircraftProgram().getId())
            .isEqualTo(A320_PROGRAM_ID);
        assertThat(oCo2).isPresent().get()
            .extracting(co -> co.getAircraftProgram().getId())
            .isEqualTo(A320_PROGRAM_ID);
        assertThat(oCo3).isPresent().get()
            .extracting(co -> co.getAircraftProgram().getId())
            .isEqualTo(A350_PROGRAM_ID);
    }

    // If you ever want per-test fixture groups, just override the hooks:
    // @Override protected void onBeforeEach() { fx.callFixturesForGroup("BeforeEach"); }
    // @Override protected void onAfterEach()  { fx.callFixturesForGroup("AfterEach"); }
}
