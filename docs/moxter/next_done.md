


---
### Move towards a "fluent API": 

replace
   public Object callFixtureReturn(String callName, String jsonPath)
with 
   moxter.callFixture(callName)
         .extract(jsonPath)

replace
    public Model.ResponseEnvelope callFixture(String name, Map<String,Object> callScoped)
with
   moxter.with(callScoped)
         .callFixture(name)


---
### Fluent API: capture return body and print

Instead of  

    JsonNode originalTracking = fx.callFixture("get_tracking", false, true).body();
    log.debug("originalTracking = {}", originalTracking.toPrettyString());


Maybe have something like:

    moxter.withLax(true)
          .withJsonPathLax(true)
          .withPrintReturn(true)
          .callFixture("get_tracking")
          
An stuff like:  

    JsonNode item = moxter.withLax(true)
                          .withJsonPathLax(true)
                          .withPrintReturn(true)
                          .callFixture("get_item")
                          .getBody()


- Insights:  
  - Path A: The "Result Wrapper" (Standard Fluent)
In this version, callFixture always returns a Result object. You decide what you want from that result after the call.

        // Configuration phase
        var silentLax = moxter.lax().withJsonPathLax(true);

        // Trigger phase + Extraction phase
        JsonNode body1 = silentLax.callFixture("step_1").body();
        JsonNode body2 = silentLax.callFixture("step_2").body();

    - Pro: Very clear. The method callFixture always returns the same type.
    - Con: You have to append .body() every single time.

  - Path B: The "Typed Executor" (Your Suggestion)
In this version, calling .returningBody() changes the "state" of the builder so that the final callFixture returns a JsonNode instead of a Result object.

        // Configuration phase (now includes the 'intent' of the return type)
        var bodyCaller = moxter.lax().returningBody(); 

        // Trigger phase (Returns JsonNode directly)
        JsonNode body1 = bodyCaller.callFixture("step_1");
        JsonNode body2 = bodyCaller.callFixture("step_2");

    - Pro: Extremely clean for repetitive tests where you only care about the JSON.
    - Con: Harder to implement in Java because callFixture would need to return Generic types (like T), or you'd have to use different method names.