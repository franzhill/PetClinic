package com.fhi.pet_clinic.model;

public enum Sex 
{
    MALE,
    FEMALE;

   public static Sex random() 
   {  return Math.random() < 0.5 ? MALE : FEMALE;
   }
}



