package com.fhi.pet_clinic.tests.transaction;



import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;


/**
 * Entity class used in TransactioRollbackTest.
 */
@Entity
@Table(name = TransactionRollbackTest.TEST_TABLE_NAME)
public class RollbackTestEntity 
{
   @Id
   @GeneratedValue(strategy = GenerationType.IDENTITY)
   private Long id;

   private String content;

   protected RollbackTestEntity() {} // for JPA

   public RollbackTestEntity(String content) 
   {   this.content = content;
   }
}
