

- Move towards a "fluent API": 

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



- define variables within moxture.yaml files:
```yaml
vars:
  USER1_ID   : "12345"
  USER2_ID   : "12"
  USER3_ID   : "123"
  USER4_ID   : "1234"
  LOCALE     : "EN"

moxtures:
...
```

- new moxture syntax 

BEFORE
```yaml
  - name: update_item
    method: PUT
    endpoint: /item/update
    expectedStatus: 200
    payload: >
      [
        {
          "objectClass": "ThirdParty",
          "eventType": "FieldUpdateEvent",
          "objectId": {{com.thirdPartyId}},
          "parentId": {{com.bcsId}},
          "fieldName": "notRelevant",
          "fieldValue": {{notRelevant}}
        }
      ]
```
AFTER

```yaml
  - name: update_item
    method: PUT
    url: /item/update   # instead of 'endpoint'
    body:  >   # instead of 'payload'
      [ ...
    save:      # keep
    expect:
      status:   # instead of expectedStatus
      body: >   
        # json return payload
```

- check returned payload


```yaml
  - name: update_item
  ...
    body:
      assert:  # selectively assert jsonpaths
        $.item.type: "new"   
        $.item.value: 100
        $.offers[*]:
           count: 3
        $offers[?(@.cost >= 1000 && @.cost <= 2000]:
           minCount:1
           maxCount:3
        $.createdAt[?(@ == 'today')]

      # OR/AND use full response matching:
      match: full|partial 
        # if full: match with json provided in value must be exact
        # if partial: provided json should be a subset of actual returned json
      value: >  # the innline json expected
      [
        {
          "type": "new",
          "value": "100",
```

- when grouping moxtures: would be nice to be able to add Thread.sleep(xxx) in between

- some kind of "Startup Banner" (that can be toggled on/off)

that prints what moxtures are available for the current class, where they're taken from, if there's any shadowing

```txt
--------------------------------------------------------
 MOXTER : PetIntegrationTest 
--------------------------------------------------------
 LOADED MOXTURES:
  [global]  login, get_health
  [local]   create_pet
  [SHADOW]  apply_shampoo (overriding global definition)
--------------------------------------------------------
```


- The "Collision Alert" (Safety without Complexity) (this can be a desired effect though, the warning should be just a "heads up") : " variable xyz defined in fixture f is overridden (what would be the right term btw? override/overwrite/shadow/... ?)

- Postman-style collection of moxtures?













Feature,Added Value,Difficulty,Complexity Risk
Fluent API,⭐⭐⭐⭐⭐,Moderate,Low
YAML Vars,⭐⭐⭐,Easy,Low
Syntax Refresh,⭐⭐,Easy,Low
JsonPath Assert,⭐⭐⭐⭐⭐,Hard,Moderate
Partial Match,⭐⭐⭐⭐,Hard,Moderate
Banner/Alerts,⭐⭐⭐⭐,Easy,Low