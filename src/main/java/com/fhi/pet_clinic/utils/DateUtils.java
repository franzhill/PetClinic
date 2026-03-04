package com.fhi.pet_clinic.utils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;


public class DateUtils {

    private DateUtils() {}
    
  
    /**
     * Simple Date formatter with the format Year - Month - Day (e.g.: 2025-02-19).
     */
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static ZonedDateTime localDateToUTC(LocalDateTime localDateTime) {
        return localDateTime.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneId.of("UTC"));
    }
}
