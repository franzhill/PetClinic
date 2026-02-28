# The State of Unit Testing

In many software projects, unit testing is the neglected stepchild of the development lifecycle. While production code is scrutinized for clean architecture, design patterns, and performance, the test suite is often left to grow wild, resulting in a "Big Ball of Mud" that provides more frustration than safety.

<br />

## The "Spaghetti" Evolution
Because tests are rarely given the same architectural attention as feature code, they tend to evolve into a tangled mess. Without a clear framework or strategy, developers copy-paste setup logic, hardcode magic strings, and create deeply nested helper methods.

The result? A test suite where maintaining the tests takes longer than developing the features, and where nobody truly knows what a specific test is actually verifying.

<br />

## Testing the tooling, not the logic: The tautology trap
Another common mistake widely seen in modern Java testing is the "Mock Everything" approach. When developers don't have a clear sight of what testing actually is and how it shoudl be done, they tend to fall back on a "cargo cult" approach. This usually manifests as the heavy, yet uncontrolled, use of mocking (think Mockito).

In my years "around the block" across many different projects, I have all too often come across tests that look quite serious on the surface, perform a good amount of "serious" mocking here and there, but ironically, do not test any business logic altogether, ending up testing nothing much else than the actual plumbing of the language or the framework.


### Example
How many times have we seen tests like this one:
```java
@Test
testUserService
{
   User myUser1 = new User("Thomas");
   User myUser2 = new User("Jack");
   when(repo.findById(1L)).thenReturn(Optional.of(myUser1));
   when(repo.findById(2L)).thenReturn(Optional.of(myUser2));

   User user = service.getUser(1L);

   // User returned should be the right one!
   assertEquals("Thomas", result.getName());
   assertNotEquals("Jack", result.getName());
}
```
With the developer quite proud of themselves, 2 extra tests for the metrics yay! ^^.  

The actual problem with the above test being that it does little more than checking that the user service is actually properly connected to the user repository. And since we're mocking the repo, we're ending up testing that Mockito works as advertised. We have built a net full of holes while convinced we are safe.


<br />

## The "Sonar" Illusion
As long as "Sonar test coverage" metrics look good, everyone looks the other way. We trade real confidence for a green percentage bar. We celebrate 80% coverage while our "safety net" wouldn't catch a single real-world regression in our intricate business algorithms. Meanwhile, the test suite continues to grow into an unwieldy big ball of tangled spaghetti. This isn't just a technical debt—it’s a structural complacency that leaves projects vulnerable to the very bugs tests are supposed to prevent.

<br />

## Where Moxter comes in

**Moxter** was designed disrupt this cycle, by promoting real business value testing from "further up", and helping organize, build better more efficient tests faster.



