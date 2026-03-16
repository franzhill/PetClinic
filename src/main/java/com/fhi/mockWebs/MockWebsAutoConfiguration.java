package com.fhi.mockWebs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Dedicated test configuration that provisions the MockWebSocket engine
 * and safely neutralizes the outbound STOMP network layer globally.
 */
@TestConfiguration
public class MockWebsAutoConfiguration {


   /**
    * In production, the messaging template acts as the backend's "megaphone," 
    * pushing messages out to subscribed frontend clients 
    * Here since we're testing in serverless mode, mocking the WebSocket, 
    * we'll mock that component too (and not route to a non-existent STOMP broker...)
    * and use Mockito to intercept and read the outgoing broadcasts.    
    * 
    * <p><b>Note</b>: Spring will 'naturally' (because of the {@code @Primary}) replace any 
    * autowired SimpMessagingTemplate inside the actual service(s) downstream
    * of the websocket controller being called, thus allowing us to 
    * wiretap the output of the real downstream logic.
    * 
    * 
    * <p><b>Note</b>: if the downstream logic ultimately uses something other than 
    * SimpMessagingTemplate (e.g., Kafka or RabbitMQ) to broadcast its messages, 
    * our STOMP wiretap will be deaf to it. In that scenario, we would have to 
    * mock that specific messaging template as well to intercept the output.
    * 
    * Example:
    *  <pre>{@code
    *    @Bean
    *    @Primary
    *    public KafkaTemplate mockKafkaTemplate() {
    *      return Mockito.mock(KafkaTemplate.class);
    *   }
    * }</pre>
    */
   @Bean
   @Primary
   public SimpMessagingTemplate mockSimpMessagingTemplate() {
       return Mockito.mock(SimpMessagingTemplate.class);
   }

   @Bean
   public MockWebs mockWebSocket(ApplicationContext context, 
                                 SimpMessagingTemplate template, 
                                 ObjectMapper mapper) {
        return new MockWebs(context, template, mapper);
   }
}