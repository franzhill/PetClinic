package com.fhi.mockWebs;

import org.springframework.context.annotation.Import;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables MockWebSocket injection and automatically mocks the 
 * SimpMessagingTemplate to prevent real network calls during testing.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(MockWebsAutoConfiguration.class)
public @interface AutoConfigureMockWebs {
}
