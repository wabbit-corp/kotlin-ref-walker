package one.wabbit.refwalker

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RefWalkerSpec {
    class Foo(val test: Map<String, Runnable>)

    @Test
    fun testGen() {
        // Print jvm options
        println(ManagementFactory.getRuntimeMXBean().inputArguments)

        val target = object : Any() {}
        val root = Foo(mutableMapOf("test" to (Runnable { println(target) })))

        println(RefWalker.findAll(listOf(RefWalker.JvmRef(root)), RefWalker.JvmRef(target)))
    }

    /**
     * 1. Simple Object Field
     *
     * A root object has one field referencing the target.
     */
    @Test
    fun testSimpleObjectField() {
        val target = Any()

        class Root(val child: Any?)
        val root = Root(target)

        val results = RefWalker.findAll(
            roots = listOf(RefWalker.JvmRef(root)),
            targetRef = RefWalker.JvmRef(target)
        )
        assertEquals(1, results.size, "Should find exactly one path to the target.")

        // Verify the path structure
        val path = results[0]
        // Typically we expect something like: Root::child
        // We'll just confirm it's not empty
        assertTrue(path.parts.isNotEmpty(), "Path should not be empty.")
    }

    /**
     * 2. Null Fields
     *
     * The code should properly skip over null references.
     */
    @Test
    fun testNullFields() {
        val target = Any()

        class Root(val nonNull: Any?, val isNull: Any?)
        val root = Root(target, null)

        val results = RefWalker.findAll(
            roots = listOf(RefWalker.JvmRef(root)),
            targetRef = RefWalker.JvmRef(target)
        )
        // Should still find exactly one path (the nonNull field), ignoring the null field
        assertEquals(1, results.size, "Should find exactly one path, ignoring null.")
    }

//    /**
//     * 3. Static Field
//     *
//     * Place the target in a static field and confirm it's found via 'Select.Static'.
//     */
//    companion object {
//        @JvmStatic private val staticTarget = Any()
//    }
//
//    @Test
//    fun testStaticField() {
//        // We'll feed the test's own class as a root
//        val results = RefWalker.findAll(
//            roots = listOf(RefWalker.JvmRef(RefWalkerSpec.Companion::class.java)),
//            targetRef = RefWalker.JvmRef(staticTarget)
//        )
//        // We expect one path: one pointing to the companion's static field
//        // The path should contain something like `Select.Static(..., "staticTarget")`
//        assertEquals(1, results.size, "Should find exactly one static reference.")
//    }

    /**
     * 4. List Reference
     *
     * The target is placed in a List.
     */
    @Test
    fun testListReference() {
        val target = Any()
        val list = listOf(Any(), target, Any())
        class Root(val stuff: List<Any>)
        val root = Root(list)

        val results = RefWalker.findAll(
            roots = listOf(RefWalker.JvmRef(root)),
            targetRef = RefWalker.JvmRef(target)
        )
        assertEquals(1, results.size, "Should find target in the list exactly once.")
        // The path likely ends with something like [Index(1)]
    }

    /**
     * 5. Map Reference
     *
     * Target is the key and then the value in a Map.
     */
    @Test
    fun testMapReference() {
        val targetKey = Any()
        val targetValue = Any()
        val map: MutableMap<Any, Any> = mutableMapOf(
            targetKey to "someValue",
            "otherKey" to targetValue
        )
        class Root(val map: Map<Any, Any>)
        val root = Root(map)

        val resultsKey = RefWalker.findAll(
            roots = listOf(RefWalker.JvmRef(root)),
            targetRef = RefWalker.JvmRef(targetKey)
        )
        assertEquals(1, resultsKey.size, "Should find targetKey exactly once.")

        val resultsValue = RefWalker.findAll(
            roots = listOf(RefWalker.JvmRef(root)),
            targetRef = RefWalker.JvmRef(targetValue)
        )
        assertEquals(1, resultsValue.size, "Should find targetValue exactly once.")
    }

    /**
     * 6. Array of Objects
     *
     * Target is placed in an array of references.
     */
    @Test
    fun testArrayOfObjects() {
        val target = Any()
        val array = arrayOf(Any(), target, Any())

        class Root(val arr: Array<Any>)
        val root = Root(array)

        val results = RefWalker.findAll(
            roots = listOf(RefWalker.JvmRef(root)),
            targetRef = RefWalker.JvmRef(target)
        )
        assertEquals(1, results.size, "Should find target in the object array.")
    }

    /**
     * 7. Primitive Array
     *
     * Should be skipped or not produce any references for the target,
     * because primitive arrays are not traversed (RefWalker checks isPrimitive).
     */
    @Test
    fun testPrimitiveArray() {
        val target = Any()
        val primArray = intArrayOf(1, 2, 3)

        class Root(val arr: IntArray, val reference: Any)
        val root = Root(primArray, target)

        // Searching for target in a root that has a primitive array
        val results = RefWalker.findAll(
            roots = listOf(RefWalker.JvmRef(root)),
            targetRef = RefWalker.JvmRef(target)
        )
        // The target is only in root.reference, not in the array
        assertEquals(1, results.size, "Should not crash or find references in a primitive array.")
    }

    /**
     * 8. Superclass Fields
     *
     * We'll place the target in a superclass field not declared in the subclass.
     */
    open class Parent(val parentField: Any?)
    class Child(parentField: Any?, val childField: Any?) : Parent(parentField)

    @Test
    fun testSuperclassFields() {
        val target = Any()
        val child = Child(target, null)

        val results = RefWalker.findAll(
            roots = listOf(RefWalker.JvmRef(child)),
            targetRef = RefWalker.JvmRef(target)
        )
        assertEquals(1, results.size, "Should find the target in the parentField.")
    }

    /**
     * 9. Graph with Duplicate Paths
     *
     * The same target object is reachable via multiple distinct references. We expect multiple paths.
     */
    @Test
    fun testGraphWithDuplicatePaths() {
        val target = Any()

        class Root(val field1: Any?, val field2: Any?)
        val root = Root(target, target)

        val results = RefWalker.findAll(
            roots = listOf(RefWalker.JvmRef(root)),
            targetRef = RefWalker.JvmRef(target)
        )
        // We expect 2 distinct paths
        assertEquals(2, results.size, "Should find two distinct references to the same target.")
    }

    /**
     * 10. Cycle Detection
     *
     * Confirm the BFS does not infinite-loop on a cycle.
     */
    @Test
    fun testCycleDetection() {
        class Node(var next: Node?)
        val node1 = Node(null)
        val node2 = Node(null)
        node1.next = node2
        node2.next = node1  // cycle

        val target = node2
        val results = RefWalker.findAll(
            roots = listOf(RefWalker.JvmRef(node1)),
            targetRef = RefWalker.JvmRef(target)
        )
        // We don't crash. Should find exactly one path from node1 -> node2.
        assertEquals(1, results.size, "Should find node2 exactly once despite the cycle.")
    }

    /**
     * 11. Nested Maps, Lists, and Arrays
     */
    @Test
    fun testNestedCollections() {
        val target = Any()

        val nestedList = listOf(
            mapOf(
                "sublist" to listOf(target, "anotherValue")
            )
        )

        class Root(val data: List<Map<String, Any>>)
        val root = Root(nestedList)

        val results = RefWalker.findAll(
            roots = listOf(RefWalker.JvmRef(root)),
            targetRef = RefWalker.JvmRef(target)
        )
        assertEquals(1, results.size, "Should find exactly one nested reference to the target.")
    }

//    /**
//     * 12. Ignored Classes
//     *
//     * For demonstration, we put the target inside a String. Normally
//     * we'd say it doesn't matter because strings are in 'ignoredClasses'.
//     * This is contrived but shows the code won't find it, by design.
//     *
//     * NOTE: The code itself doesn't store references inside a String, so
//     * this test mostly shows that searching inside a String is skipped.
//     */
//    @Test
//    fun testIgnoredClasses() {
//        val target = ByteArray(1) { 0x42 }
//        // There's no direct standard way to embed an arbitrary object in a String,
//        // but let's just demonstrate that if the root is a String, it won't be scanned.
//        // We'll just do a contrived check:
//        val rootString = "Pretend there's an object reference here"
//
//        val field = rootString.javaClass.getDeclaredField("value")
//        field.isAccessible = true
//        field.set(rootString, target)
//
//        val results = RefWalker.findAll(
//            roots = listOf(RefWalker.JvmRef(rootString)),
//            targetRef = RefWalker.JvmRef(target)
//        )
//        // We expect 0, because RefWalker ignores Strings
//        assertEquals(0, results.size, "Target should not be found inside an ignored class.")
//    }

//    /**
//     * 13 & 14. Class Loader Tests
//     *
//     * For this to be meaningful, you'd want a scenario where you actually
//     * load classes in different class loaders. We'll just do a trivial check.
//     */
//    @Test
//    fun testMatchingClassLoader() {
//        val target = Any()
//        class Root(val ref: Any)
//        val root = Root(target)
//
//        // We'll guess that Root is loaded by the same ClassLoader as our test
//        val classLoader = root.javaClass.classLoader
//
//        val results = RefWalker.findAllByClassLoader(
//            roots = listOf(RefWalker.JvmRef(root)),
//            classLoader = classLoader
//        )
//        // The root itself is loaded by the matching class loader
//        // so we expect a path to root. However, 'findAllByClassLoader' returns
//        // paths for discovered objects whose class loader matches, not for a specific object identity.
//        // We'll check we have at least 1 path that references Root or its content:
//        assertTrue(results.isNotEmpty(), "Should detect references loaded by the same class loader.")
//    }

    @Test
    fun testDifferentClassLoader() {
        // We won't spin up a custom class loader here, but let's show the usage:
        val target = Any()
        val root = target // same object

        // We'll pass in a nonsense class loader or a new instance if you can.
        val dummyClassLoader = object : ClassLoader() {}

        val results = RefWalker.findAllByClassLoader(
            roots = listOf(RefWalker.JvmRef(root)),
            classLoader = dummyClassLoader
        )
        assertTrue(results.isEmpty(), "Should not find references for a different class loader.")
    }

    /**
     * 15. Security Manager or Reflection Restriction
     *
     * We can't fully demonstrate in typical test code, but you could set up a restricted environment
     * and confirm partial coverage or logs. For now, just a placeholder.
     */
    @Test
    fun testSecurityManagerPlaceholder() {
        // Typically you'd do something special with a real SecurityManager, or a custom policy.
        // We'll skip the actual test as it's environment-specific.
        assertTrue(true, "Placeholder for a restricted-environment reflection test.")
    }

    /**
     * 16. Corrupted or Special Collections
     *
     * We'll define a custom list that throws on iteration, forcing fallback to JvmDict scanning.
     */
    class SpecialListThatFails : List<Any?> {
        override val size: Int get() = throw UnsupportedOperationException("Boom!")
        override fun contains(element: Any?): Boolean = throw UnsupportedOperationException("Boom!")
        override fun containsAll(elements: Collection<Any?>): Boolean = throw UnsupportedOperationException("Boom!")
        override fun get(index: Int): Any? = throw UnsupportedOperationException("Boom!")
        override fun indexOf(element: Any?): Int = throw UnsupportedOperationException("Boom!")
        override fun isEmpty(): Boolean = throw UnsupportedOperationException("Boom!")
        override fun iterator(): Iterator<Any?> = throw UnsupportedOperationException("Boom!")
        override fun lastIndexOf(element: Any?): Int = throw UnsupportedOperationException("Boom!")
        override fun listIterator(): ListIterator<Any?> = throw UnsupportedOperationException("Boom!")
        override fun listIterator(index: Int): ListIterator<Any?> = throw UnsupportedOperationException("Boom!")
        override fun subList(fromIndex: Int, toIndex: Int): List<Any?> = throw UnsupportedOperationException("Boom!")
    }

    @Test
    fun testCorruptedCollectionFallback() {
        val target = Any()
        class Root(val specialList: SpecialListThatFails, val otherRef: Any)
        val root = Root(SpecialListThatFails(), target)

        // If the code tries to interpret specialList as a List, it will fail in iteration
        // and fallback to object scanning. This might or might not reveal the reference to target
        // depending on how the fallback is triggered. In the default code, fallback is only used if
        // reflection fails, so we won't find 'target' in specialList. But 'otherRef' is still found
        // via normal field reflection.
        val results = RefWalker.findAll(
            roots = listOf(RefWalker.JvmRef(root)),
            targetRef = RefWalker.JvmRef(target)
        )
        assertEquals(
            1,
            results.size,
            "We should still find 'otherRef' even though the list fails iteration."
        )
    }
}
