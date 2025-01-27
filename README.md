1. [What This Library Does](#what-this-library-does)
2. [Why It Is Useful](#why-it-is-useful)
3. [Basic API Overview](#basic-api-overview)
4. [Example Use Cases](#example-use-cases)
    - [Debugging Memory Leaks](#debugging-memory-leaks)
    - [Finding References to a Specific Class Loader](#finding-references-to-a-specific-class-loader)
    - [Tracing Object Graph Paths](#tracing-object-graph-paths)
5. [Sample Usage](#sample-usage)

---

## What This Library Does

`RefWalker` recursively walks the object graph reachable from one or more “root” references (instances of `JvmRef`). By “object graph,” we mean:

- Non-primitive instance fields
- Static fields on classes
- Elements of `List`, `Map`, or array objects

During traversal, `RefWalker` tracks visited references and calls them out if they match a given “target” reference (or if they match certain conditions, such as a specific class loader). In other words, it’s a search for all places in memory (reachable from your chosen root set) that reference some particular object or class loader.

Key features:

1. **Reflection-based**: uses Java reflection to examine both declared and inherited fields.
2. **BFS**-style search** (with a queue) that prevents infinite loops on cyclical references by maintaining a `visited` set.
3. **Supports multiple data structures**: handles arrays, lists, maps, and plain objects.
4. **Optional ignoring** certain known classes like `String`, `Class`, `Logger`, `BigDecimal`, etc., to avoid scanning large, ubiquitous “leaf” objects that are rarely interesting.

---

## Why It Is Useful

1. **Locate references to an object**: Suppose you have an object that you suspect is leaking or not getting garbage-collected. By specifying that object as the “target,” `RefWalker` can reveal exactly which fields/collections are still holding a reference to it.

2. **Class loader leak detection**: In certain frameworks (Tomcat, OSGi, etc.), you might want to detect objects loaded by a particular class loader that should have been garbage-collected after an application redeploy. `RefWalker`’s `findAllByClassLoader` can locate everything in your heap that is still using a given loader.

3. **Debugging complex object graphs**: If you have a complicated structure of nested collections and fields, `RefWalker` can provide paths describing how the code can reach a certain object from a known root.

4. **Runtime introspection**: You may want to write a debug tool or plugin that scans a root set of references (like singletons, top-level objects) and looks for certain suspicious references, logging or analyzing them in detail.

---

## Basic API Overview

The core entry points are:

1. **`JvmRef`**  
   A wrapper for any `Any?` reference. It overrides equality to be based on reference identity (`===`), rather than structural.

2. **`RefWalker.findAll(roots, targetRef)`**  
   Given a list of roots (each wrapped in `JvmRef`) and a target reference (wrapped in `JvmRef`), returns a list of `Path` objects representing every path through the object graph that leads to the target.

    - Each `Path` is essentially a linked list of “select” steps (via `ConsList` internally).
    - A `Select` can be:
        - `Static(clazz, fieldName)`
        - `Field(clazz, fieldName)`
        - `Index(index)`

3. **`RefWalker.findAllByClassLoader(roots, classLoader)`**  
   Similar to `findAll`, but instead of looking for a specific reference, it searches for all references whose `.javaClass.classLoader` matches `classLoader`. Returns a list of `Path` objects to each discovered object.

**Important Implementation Details**:
- `RefWalker` sets fields to be accessible (`f.isAccessible = true`) in a `try/catch`, so it can read private fields.
- It keeps track of visited references in a `visited` set to avoid infinite loops.
- It also maintains a set of `ignoredClasses` (by default includes `String`, `Class`, `BigDecimal`, etc.), preventing the library from diving into uninteresting or large-literal classes.

---

## Example Use Cases

### Debugging Memory Leaks

Suppose you have an object that you believe should have been freed, but you notice it never gets garbage-collected. If you can get a handle on that object, you can call:

```kotlin
val myTarget = ...
val foundPaths = RefWalker.findAll(
    listOf(RefWalker.JvmRef(rootOfYourApp)), // e.g. a top-level object
    RefWalker.JvmRef(myTarget)
)
```

- `rootOfYourApp` might be your main application instance or some set of singletons.
- The returned `foundPaths` can show you exactly which fields are still referencing `myTarget`. You might see something like:
    - `com.example.SomeSingleton::cache -> [Index(3)] -> ... -> yourObject`

This helps you pinpoint the root cause of the leak.

### Finding References to a Specific Class Loader

If you suspect your web application’s class loader can’t unload because references remain, you can do:

```kotlin
val classLoader = myAppClassLoader
val foundPaths = RefWalker.findAllByClassLoader(
    roots = listOf(RefWalker.JvmRef(rootOfYourApp)),
    classLoader = classLoader
)
```

You’ll get a list of `Path` objects for every discovered reference loaded by `classLoader`. You can then see which singletons or other references are holding them.

### Tracing Object Graph Paths

Sometimes you just want to see all the paths from your root object to some nested data structure. For instance, you have a giant nested `Map<String, Any>` inside your application, and you want to see how many times a particular domain object is stored in there. The same approach applies:

```kotlin
val domainObj = ...
val roots = listOf(RefWalker.JvmRef(topLevelSingleton), RefWalker.JvmRef(someStaticClass)) 
val foundPaths = RefWalker.findAll(roots, RefWalker.JvmRef(domainObj))
```

You end up with zero, one, or multiple paths identifying the exact steps in nested maps, arrays, or lists leading to `domainObj`.

---

## Sample Usage

Below is a minimal snippet you could adapt to your codebase. It demonstrates:

1. Creating a root object and a target object.
2. Using `RefWalker.findAll` to locate the target.
3. Printing out the path results.

```kotlin
import one.wabbit.refwalker.RefWalker

fun main() {
    // 1. Suppose we have a root structure
    class MyRoot(val fieldA: Any?, val fieldB: Any?)
    val targetObj = Any()  // The object we want to find references to

    val root = MyRoot(
        fieldA = listOf("string1", targetObj, 42),
        fieldB = mapOf("key" to "value")
    )

    // 2. Wrap them in JvmRef
    val rootRef = RefWalker.JvmRef(root)
    val targetRef = RefWalker.JvmRef(targetObj)

    // 3. Call findAll
    val results = RefWalker.findAll(listOf(rootRef), targetRef)

    // 4. Print out the discovered paths
    println("Found ${results.size} path(s) referencing targetObj.")
    for ((index, path) in results.withIndex()) {
        println("Path #$index: " + path.parts.joinToString(" -> "))
    }

    // Each path step is a Select: could be Field, Index, Static, etc.
}
```

When you run this, you might see something like:

```
Found 1 path(s) referencing targetObj.
Path #0: MyRoot::fieldA -> [1]
```

That indicates `fieldA` references a list, and index 1 of that list is `targetObj`.

---

### Tips / Caveats

1. **Kotlin Companion Objects**: If you want to track a field declared in a Kotlin companion object, note that the “static field” might live on the companion object’s generated class rather than the outer class.
2. **Ignored Classes**: By default, `String`, `Class`, `Logger`, `BigDecimal`, and `BigInteger` are in an “ignored” set. If you need to search inside them, remove them from the ignored set in the `SearchState`.
3. **Performance**: On large object graphs, the BFS can become expensive. You may want to prune or limit your roots or ignore classes you don’t need to scan.
4. **Security Manager**: If a custom SecurityManager blocks reflection, some fields may not be accessible. The code already tries/catches exceptions, but it might skip them or produce partial results.

---

## Conclusion

`RefWalker` provides a handy reflection-based approach to analyzing object graphs at runtime. Whether you’re debugging memory leaks, investigating class loader references, or just exploring how certain objects are connected, `RefWalker` can give you a detailed map from root objects to your target references. By combining BFS traversal, reflection on non-primitive fields, and specialized handling of arrays/lists/maps, it offers an easy way to see “who’s holding onto what” in your application.