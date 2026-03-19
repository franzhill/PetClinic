
---

## capture return body and print


        JsonNode originalTracking = fx.callFixture("get_tracking", false, true).body();
        log.debug("originalTracking = {}", originalTracking.toPrettyString());



## assert against a call-scope variable

Instead of 
```java

      String pContent = "I am userA and this is my first comment";
      callerA  .withVar("p.content", pContent)
               .call("cke.create_ckeditor_thread")
               .assertBody("$.comments[0].content")
                  .isEqualTo(pContent).and()
               .assertBody("$.threadId").isNotNull();
```

this can be done:

```java
      callerA  .withVar("p.content", "I am userA and this is my first comment")
               .call("cke.create_ckeditor_thread")
               .assertBody("$.comments[0].content")
                  .isEqualToInterpolated("${p.content}").and()
               .assertBody("$.threadId").isNotNull();
```


## using anchor injection in YAML files

```yaml

# =================================================
# Templates: re-usable static blocks
# =================================================

.templates:

  ckeVars: &ckeVars     # anchor
      p.bcsId                  : ${com.bcsId}
      p.bcsIssueId             : ${com.bcsIssueId}
      p.parentId               : ${com.bcsId}

      p.objectId               : ${com.thirdPartyId}
      p.objectClass            : "ThirdParty"
      p.fieldName              : "commentCommercial"

...


  - name: cke.stomp_lock_cke_field
    protocol: stomp
    endpoint: "/ckeditor/field/${p.objectClass}/${p.objectId}/${p.fieldName}/publish"
    vars:
      <<: *ckeVars   # anchor block is injected
      p.userLockId             : "lock-${mx.uuid()}"

```

This only works past a certain version of the snakeyaml parser library used (>2.0) so if 
this not available on your project, another non-yaml-native mechanism is offered by Moxter: 

```yaml

# =================================================
# Templates: re-usable static blocks
# =================================================

.templates:

  ckeVars:
      p.bcsId                  : ${com.bcsId}
      p.bcsIssueId             : ${com.bcsIssueId}
      p.parentId               : ${com.bcsId}

      p.objectId               : ${com.thirdPartyId}
      p.objectClass            : "ThirdParty"
      p.fieldName              : "commentCommercial"

...

  - name: cke.update_cke_field_bulk
    extends: com.update_field_bulk
    vars:
      __template__: ckeVars  # replaces with what is at .templates.ckeVars


```

Whatever injection mechanism chosen, the injection is done early in the
processing of the moxture file, before any interpolation or resolution happens.
