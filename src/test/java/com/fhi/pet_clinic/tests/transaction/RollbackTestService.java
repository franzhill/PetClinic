package com.fhi.pet_clinic.tests.transaction;


import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service class used in TransactioRollbackTest.
 */
@Service
public class RollbackTestService 
{
   private final RollbackTestRepository rtRepository;

   public RollbackTestService(RollbackTestRepository rep) 
   {  rtRepository = rep;
   }

   @Transactional
   public void saveAndFail(String content) 
   {  rtRepository.save(new RollbackTestEntity(content));
      throw new RuntimeException(TransactionRollbackTest.MSG_RUNTIME_EXCEPTION);
   }

   @Transactional
   public void saveWithoutFail(String content) 
   {  rtRepository.save(new RollbackTestEntity(content));
      // No exception thrown here ;)
   }
}