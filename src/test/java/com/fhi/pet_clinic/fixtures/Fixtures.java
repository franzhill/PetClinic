package com.fhi.pet_clinic.fixtures;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Fixtures {
    Class<?>[] value();
}