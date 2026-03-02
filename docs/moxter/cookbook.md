
---

## capture return body and print


        JsonNode originalTracking = fx.callFixture("get_tracking", false, true).body();
        log.debug("originalTracking = {}", originalTracking.toPrettyString());
