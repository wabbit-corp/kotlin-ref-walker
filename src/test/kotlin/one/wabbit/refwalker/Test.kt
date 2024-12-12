package one.wabbit.refwalker

import java.lang.management.ManagementFactory
import kotlin.test.Test

class Test {
    class Foo(val test: Map<String, Runnable>)

    @Test
    fun testGen() {
        // Print jvm options
        println(ManagementFactory.getRuntimeMXBean().inputArguments)

        val target = object : Any() {}
        val root = Foo(mutableMapOf("test" to (Runnable { println(target) })))

        println(RefWalker.findAll(listOf(RefWalker.JvmRef(root)), RefWalker.JvmRef(target)))
    }
}
