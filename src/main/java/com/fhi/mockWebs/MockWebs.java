package com.fhi.mockWebs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.authorization.method.PreAuthorizeAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.util.SimpleMethodInvocation;
import org.springframework.stereotype.Controller;
import org.springframework.util.AntPathMatcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * A testing utility to bypass the STOMP network layer.
 * 
 * Intended to act as an alter-ego to MockMvc, for WebSocket interactions.
 * 
 * It allows feeding messages into its controller and reading broadcasts from 
 * a mocked SimpMessagingTemplate.
 * 
 * <p><b>More explanations:</b>
 * - Spring Boot does not provide a MockMvc equivalent for STOMP WebSockets so this 
 *   is why we have built this class ;)
 * - When we @Autowire MockMvc, Spring actually boots up a fake DispatcherServlet.
 *   This fake servlet scans the entire application context, finds every single 
 *   @RestController, reads all their @RequestMapping annotations, and builds a massive
 *   routing table in memory.
 * - This utility replicates that exact mechanism. It scans the ApplicationContext, 
 *   maps all @MessageMapping and @SubscribeMapping endpoints into memory, and routes 
 *   payloads directly to the underlying Java methods using reflection.
 * 
 * <p><b>Critical requirement:</b>
 * - This utility relies entirely on Mockito's {@code verify()} and {@code ArgumentCaptor} 
 *   to inspect outbound messages. Therefore, the {@link SimpMessagingTemplate} injected 
 *   into this utility <b>MUST</b> be a Mockito mock or spy. See constructor.
 * 
 * 
 */
@Slf4j
public class MockWebs 
{

    /**
    * The mocked or spied messaging template.
    * Used as a wiretap to intercept and verify outbound broadcast(ed) messages.
    */
   private final SimpMessagingTemplate template;

   /**
    * The Jackson object mapper.
    * Used to manually deserialize incoming JSON payloads and outgoing broadcast(ed) messages.
    */
   private final ObjectMapper mapper;

   /**
    * Spring's built-in tool for matching ant-style URLs.
    * Allows us to extract variables from paths (e.g., matching "/path/200" to "/path/{id}").
    */
   private final AntPathMatcher pathMatcher = new AntPathMatcher();

   /**
    * The internal in-memory routing table.
    * Populated at startup, it maps STOMP destination patterns to their corresponding Java methods.
    */
   private final List<Route> routes = new ArrayList<>();

   /**
    * Represents a single STOMP endpoint registered in the system.
    * 
    * @param pattern    The destination template (e.g., 
    *                   "/editor/field/{entityClass}/{objectId}/publish")
    * @param controller The Spring singleton controller instance that handles the request
    * @param method     The specific Java method to invoke via reflection
    */
   private record Route(String pattern, Object controller, Method method) {}

    /** 
     * Manager for evaluating @PreAuthorize expressions. 
     */
    private final PreAuthorizeAuthorizationManager authManager = new PreAuthorizeAuthorizationManager();

   /**
    * Constructs the MockWebSocket utility. 
    * 
    * Scans the ApplicationContext ONCE to build an in-memory STOMP routing table.
    * 
    * @param context    The Spring application context to scan for STOMP controllers.
    * @param template   A <b>Mockito-mocked or spied</b> SimpMessagingTemplate
    *                   (typically provided via {@code @MockBean} or {@code @SpyBean} 
    *                   in your test configuration). 
    *                   This acts as our "read" pipe to intercept and verify outbound 
    *                   broadcast(ed) messages sent by the backend.
    *                   If a real bean is passed, outbound verification will fail 
    *                   (with a {@code NotAMockException}).
    * @param mapper     The Jackson ObjectMapper used to deserialize the captured broadcast(ed) 
    *                   payloads back into strongly-typed Java objects for test assertions.
    */
    public MockWebs(ApplicationContext context, SimpMessagingTemplate template, ObjectMapper mapper)
    {
        // Fail Fast Guard: Enforce Mockito contract
        if (!Mockito.mockingDetails(template).isMock() && !Mockito.mockingDetails(template).isSpy()) 
        {  throw new IllegalArgumentException(
                "The SimpMessagingTemplate passed to MockWebs MUST be a Mockito Mock or Spy! " +
                "Please ensure you are using @MockBean or @SpyBean in your test configuration."
            );
        }
        this.template = template;
        this.mapper = mapper;

        log.debug("[MockWebs] Initializing routing table...");
        Map<String, Object> controllers = context.getBeansWithAnnotation(Controller.class);

        if (controllers.isEmpty()) {
                log.warn("[MockWebs] No @Controller beans found in ApplicationContext. Routing will be empty!");
        }

        for (Object controller : controllers.values()) 
        {
            // 1. Get the actual user-defined class (strips the CGLIB proxy)
            Class<?> targetClass = AopUtils.getTargetClass(controller);
            log.debug("[MockWebs] Controller Found: {} (Target: {})", 
                    controller.getClass().getSimpleName(), targetClass.getSimpleName());

            // 2. Use Spring's MethodIntrospector to find methods with @MessageMapping
            //   (these are triggered when a frontend client sends a STOMP SEND command.
            // Because controllers can be wrapped in transactions or security proxies, 
            // this entails that standard Java reflection only sees the generated proxy
            // class, which lacks the original annotations. As a qonsequence, a basic scan
            // fails to find any routes. So, we use MethodIntrospector to "pierce" the proxy
            // and find the real @MessageMapping metadata on the underlying target class.
            Map<Method, MessageMapping> messageMappings = MethodIntrospector.selectMethods(targetClass,
                    (MethodIntrospector.MetadataLookup<MessageMapping>) method -> 
                            AnnotatedElementUtils.findMergedAnnotation(method, MessageMapping.class));

            messageMappings.forEach((method, annotation) -> {
                for (String mapping : annotation.value()) {
                    routes.add(new Route(mapping, controller, method));
                    log.debug("[MockWebs] Registered MessageMapping: {} -> {}.{}()", 
                            mapping, targetClass.getSimpleName(), method.getName());
                }
            });

            // 3. Repeat for @SubscribeMapping
            Map<Method, SubscribeMapping> subscribeMappings = MethodIntrospector.selectMethods(targetClass,
                    (MethodIntrospector.MetadataLookup<SubscribeMapping>) method -> 
                            AnnotatedElementUtils.findMergedAnnotation(method, SubscribeMapping.class));

            subscribeMappings.forEach((method, annotation) -> {
                for (String mapping : annotation.value()) {
                    routes.add(new Route(mapping, controller, method));
                    log.debug("[MockWebs] Registered SubscribeMapping: {} -> {}.{}()", 
                            mapping, targetClass.getSimpleName(), method.getName());
                }
            });

            // 4. Initialize the Security Manager with the ApplicationContext
            // This allows @PreAuthorize expressions to reference other beans via SpEL 
            // (e.g. @myService.check())
            DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
            handler.setApplicationContext(context); 
            this.authManager.setExpressionHandler(handler);
        }
        log.info("[MockWebs] Initialization complete. Total routes: {}", routes.size());
    }

   /**
    * Creates a lightweight session bound to a specific user.
    * This provides a clean, fluent API for tests, allowing you to avoid passing the Authentication 
    * token repeatedly without rebuilding the heavy routing table.
    * 
    * @param principal The user's Authentication or Principal object.
    * @return A StompSession instance tied to the provided principal.
    */
   public StompSession with(Object principal) 
   {   return new StompSession(this, principal);
   }


    /**
     * A lightweight wrapper that remembers the user identity for subsequent WebSocket calls.
     */
    public record StompSession(MockWebs engine, Object principal) 
    {
        /**
         * Sends a STOMP message as the session's bound user.
         * 
         * @param destination The raw STOMP target destination (path) (e.g., "/field/account/200/publish")
         * @param jsonPayload The JSON string payload.
         * @return The object returned by the controller method.
         */
        public Object send(String destination, String jsonPayload) throws Exception {
            return engine.send(destination, jsonPayload, principal);
        }

        /**
         * Sends a STOMP message as the session's bound user using a JsonNode payload.
         * 
         * @param destination The raw STOMP target destination (path) (e.g., "/field/account/200/publish")
         * @param payload     The payload as a Jackson JsonNode.
         * @return The object returned by the controller method.
         */
        public Object send(String destination, JsonNode payload) throws Exception {
            // Delegate to the engine using the standardized string representation
            return engine.send(destination, payload.toString(), principal);
        }
    }



    /**
     * Emulates STOMP routing by destination URL, acting as a true alter-ego to MockMvc.
     * 
     * @param destination The raw STOMP target destination (path) (e.g., "/field/account/200/publish")
     * @param jsonPayload The message payload
     * @param principal   The user's Authentication or Principal object
     * @return The object returned by the controller method (useful for @SubscribeMapping assertions).
     */
    public Object send(String destination, String jsonPayload, Object principal) throws Exception 
    {
        log.debug("[MockWebs] Attempting to route message to: {}", destination);

        // --------------------------------------------------------------------
        // PHASE 0: IDENTITY SETUP (Mimic MockMvc filter behavior)
        // --------------------------------------------------------------------
        Authentication auth = (principal instanceof Authentication a) ? a : null;
        SecurityContext originalContext = SecurityContextHolder.getContext();
        
        try 
        {
            // Temporarily set the context so downstream logic or SpEL can access the user
            if (auth != null) 
            {   SecurityContext context = SecurityContextHolder.createEmptyContext();
                                context.setAuthentication(auth);
                SecurityContextHolder.setContext(context);
            }

            // --------------------------------------------------------------------
            // PHASE 1: THE ROUTER (Scanning our in-memory registry)
            // --------------------------------------------------------------------
            // Instead of querying the Spring ApplicationContext on every test call, we scan 
            // the lightweight 'routes' list we built during the class constructor.
            for (Route route : routes) 
            {
                // Spring's AntPathMatcher checks if the actual URL (e.g., ".../BCS/200/...") 
                // matches the route's template (e.g., ".../{entityClass}/{objectId}/...")
                if (pathMatcher.match(route.pattern(), destination)) 
                {

                    // ----------------------------------------------------------
                    // PHASE 2: VARIABLE EXTRACTION
                    // ----------------------------------------------------------
                    // Extracts the values from the URL based on the matched template.
                    // Example: pathVars will contain {"objectId": "200", "entityClass": "BCS"}
                    Map<String, String> pathVars = 
                            pathMatcher.extractUriTemplateVariables(route.pattern(), destination);

                    // We prepare an array to hold the exact arguments we will pass to the Java method
                    Object[] args = new Object[route.method().getParameterCount()];
                    Parameter[] parameters = route.method().getParameters();

                    // ----------------------------------------------------------
                    // PHASE 3: THE BINDING PROCESS (Mapping data to method parameters)
                    // ----------------------------------------------------------
                    for (int i = 0; i < parameters.length; i++) 
                    {
                        Parameter param = parameters[i];
                        
                        // --- 3A. Handle @DestinationVariable ---
                        // If the parameter expects a value from the URL (like @PathVariable in REST)
                        if (param.isAnnotationPresent(DestinationVariable.class)) {
                            
                            // Get the variable name. If the annotation doesn't specify it, use the 
                            // parameter's name
                            String varName = param.getAnnotation(DestinationVariable.class).value();
                            if (varName.isEmpty()) varName = param.getName();
                            
                            // Get the raw string value from the extracted URL variables
                            String strValue = pathVars.get(varName);
                            
                            // Perform basic type casting (Spring normally does this automatically)
                            if (param.getType().equals(Long.class) || 
                                param.getType().equals(long.class)) 
                            {     args[i] = Long.parseLong(strValue);
                            }
                            else if (param.getType().equals(String.class)) {
                                args[i] = strValue;
                            } 
                            else if (param.getType().equals(Integer.class) || 
                                    param.getType().equals(int.class)) 
                            {   args[i] = Integer.parseInt(strValue);
                            }
                        } 
                        
                        // --- 3B. Handle Security Context (Principal/Authentication) ---
                        // If the method expects the logged-in user, we inject the principal passed to this mock
                        else if (principal != null && param.getType().isAssignableFrom(principal.getClass())) {
                            args[i] = principal;
                        }
                        
                        // --- 3C. Handle The Payload (The "Message Converter" Simulation) ---
                        // If it's not a URL variable and not the Principal, it MUST be the payload body.
                        // Because we bypassed the real STOMP broker, we also bypassed Spring's 
                        // MappingJackson2MessageConverter. We must manually deserialize the JSON string
                        // into the exact Java class the method demands.
                        else {
                            if (jsonPayload == null || jsonPayload.isBlank()) {
                                args[i] = null; // No payload provided, pass null
                            } else {
                                try {
                                    args[i] = mapper.readValue(jsonPayload, param.getType());
                                } catch (Exception e) {
                                    throw new IllegalArgumentException(
                                        "MockWebSocket failed to deserialize JSON into " + param.getType().getSimpleName() + 
                                        ". Please check your JSON structure.", e);
                                }
                            }
                        }
                    }
               
                    // ----------------------------------------------------------
                    // PHASE 3.5: SECURITY VERIFICATION
                    // ----------------------------------------------------------
                    // We wrap the method and bound arguments into a MethodInvocation
                    // and ask the manager to verify access.
                    checkSecurity(route, args, auth);

                    // ----------------------------------------------------------
                    // PHASE 4: EXECUTION
                    // ----------------------------------------------------------
                    // If checkSecurity didn't throw an AccessDeniedException, we proceed.
                    // We found the method and successfully built the arguments. 
                    // Now we invoke the method directly using Java Reflection, completely 
                    // bypassing the network layer.
                    Object returnValue;
                    try 
                    {   returnValue = route.method().invoke(route.controller(), args);
                    } 
                    catch (InvocationTargetException e) 
                    {   // Unwrap the reflection exception so the test sees the REAL business exception
                        // (e.g., NotFoundException)
                        Throwable cause = e.getCause();
                        if (cause instanceof Exception) 
                        {   throw (Exception) cause;
                        }
                        throw e;
                    }

                    // ----------------------------------------------------------
                    // PHASE 5: THE INTERCEPTOR EMULATION (@SendTo / @SendToUser)
                    // ----------------------------------------------------------
                    if (returnValue != null) 
                    {
                        // Emulate @SendTo
                        if (route.method().isAnnotationPresent(SendTo.class)) {
                            String[] outboundDestinations = route.method()
                                                                .getAnnotation(SendTo.class).value();
                            
                            for (String outboundDest : outboundDestinations) 
                            {
                                // Resolve placeholders (e.g., {objectId} -> 200)
                                String resolvedDest = outboundDest;
                                for (Map.Entry<String, String> entry : pathVars.entrySet()) {
                                    resolvedDest = resolvedDest.replace("{" + entry.getKey() + "}",
                                                                        entry.getValue());
                                }
                                
                                // Push it through our Mockito Megaphone
                                // This routes the return value directly into our wiretap (verifyBroadcast)
                                template.convertAndSend(resolvedDest, returnValue);
                            }
                        } 
                        
                        // Emulate @SendToUser (e.g., your ExceptionHandler)
                        else if (route.method().isAnnotationPresent(SendToUser.class)) 
                        {
                            String[] userDestinations = route.method()
                                                             .getAnnotation(SendToUser.class).value();
                            String username = (principal instanceof Principal p) 
                                                    ? p.getName() 
                                                    : "unknown-test-user";
                            
                            for (String userDest : userDestinations) {
                                template.convertAndSendToUser(username, userDest, returnValue);
                            }
                        }
                    }

                    // Return the payload directly so @SubscribeMapping tests can assert on it!
                    return returnValue;
                }
            }
        }
        finally 
        {   // Restore the original context to prevent state leakage between test steps
            SecurityContextHolder.setContext(originalContext);
        }

        throw new IllegalArgumentException("404 Not Found: No @MessageMapping matched destination -> " + destination);
    }


    /**
     * Manually triggers the Spring Security evaluation for @PreAuthorize.
     * Throws AccessDeniedException if the current identity is unauthorized.
     */
    private void checkSecurity(Route route, Object[] args, Authentication auth) 
    {
        // We create an AOP MethodInvocation which is what the AuthorizationManager expects.
        // This allows the manager to find the @PreAuthorize annotation on the method OR class.
        org.aopalliance.intercept.MethodInvocation mi = 
                new SimpleMethodInvocation(route.controller(), route.method(), args);
        
        // The .verify() method performs the SpEL check and throws AccessDeniedException on failure.
        this.authManager.verify(() -> auth, mi);
    }


    /**
     * Verifies that the server successfully broadcasted a message to a specific topic 
     * (i.e., notified other users).
     * 
     * <p><b>Architectural Context:</b> 
     * In a real-time collaborative feature, a user action (like locking a field) triggers
     * two distinct responses:
     * - The Direct Reply: The server replies directly to the sender confirming their action. 
     *   This is captured by the return value of {@code mockWs.send(...)}.
     * - The Broadcast: The server pushes an event to a shared {@code /topic/...} to update 
     *   the screens of all other connected clients.
     * 
     * <p><b>Explanation:</b>
     * With mockWebs.send(), we fake the frontend sending a message 
     * to the server (Inbound flow). This here is the exact opposite: we're wiretapping 
     * on the Outbound flow, scrutinizing the outgoing traffic to ensure 
     * the server actually followed through on broadcasting the update to the rest of the clients.
     * 
     * <p><b>Example Usage:</b>
     * <pre>{@code
     *   // 1. Trigger the action (Inbound)
     *   String destination = "/editor/field/Item/4317/comment/publish";
     *   sessionUserA.send(destination, lockPayloadJson);
     *   
     *   // 2. Verify the backend notified everyone else (Outbound)
     *   String topic = "/topic/editor/field/Item/4317/comment/published";
     *   String broadcastedJson = mockWs.verifyBroadcast(topic, String.class);
     *      // => this makes sure *a* message was broadcasted on this topic.
     *   
     *   // 3. Additional check: assert the content of the broadcast(ed) message:
     *   assertThat(broadcastedJson).contains("\"event\":\"CKCONTENT_LOCK\"");
     * }</pre>
     * 
     * @param expectedTopicSubstring A unique part of the destination topic (e.g.,
     *                               "/topic/editor/field/.../published") on which a message 
     *                               is supposed to be broadcast(ed).
     * @param payloadClass           The expected class to deserialize the broadcast(ed) JSON payload
     *                               into (often {@code byte[].class} or a specific DTO).
     * @param <T>                    The type of the expected payload.
     * @return The captured broadcast(ed) payload, allowing possible further assertions to be performed
     *         on the exact data sent to other clients.
     * @throws Exception If no matching broadcast is found on the outbound channel, or if 
     *         deserialization fails.
     */
    public <T> T verifyBroadcast(String expectedTopicSubstring, Class<T> payloadClass) throws Exception 
    {
        // 1. Prepare Mockito interceptors to extract the exact arguments passed to the template
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        // 2. Instantly check the Mockito ledger. 
        // This asserts that the backend called template.convertAndSend() at least once during this test.
        // It requires absolutely no time/waiting because the execution was synchronous.
        verify(template, atLeastOnce()).convertAndSend(topicCaptor.capture(), payloadCaptor.capture());

        // 3. Extract the last captured invocation from the ledger
        String actualTopic = topicCaptor.getValue();
        Object actualPayload = payloadCaptor.getValue();

        // 4. Verify the backend routed the message to the correct destination
        assertThat(actualTopic)
            .as("Expected the broadcast destination to contain '%s', but was '%s'", expectedTopicSubstring, actualTopic)
            .contains(expectedTopicSubstring);

        // 5. Deserialize the payload back into a strongly-typed object for test assertions
        if (payloadClass.isInstance(actualPayload)) {
            return payloadClass.cast(actualPayload);
        } else if (actualPayload instanceof String) {
            return mapper.readValue((String) actualPayload, payloadClass);
        } else if (actualPayload instanceof byte[]) {
            return mapper.readValue((byte[]) actualPayload, payloadClass);
        } else {
            throw new IllegalArgumentException("Cannot deserialize payload of type " + actualPayload.getClass());
        }
    }



}