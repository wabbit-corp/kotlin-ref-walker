package one.wabbit.refwalker

import one.wabbit.data.ConsList
import one.wabbit.data.consListOf
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.math.BigInteger
import java.util.logging.Logger

// https://gist.github.com/alexknvl/10e66d8ccae1b6f2461b1216913cc2fd
object RefWalker {
    class JvmRef(val value: Any?) {
        val isNull: Boolean
            get() = value === null

        override fun equals(other: Any?): Boolean =
                if (other !is JvmRef) false
                else other.value === this.value

        override fun hashCode(): Int =
                if (this.value === null) 0xcafebabe.toInt()
                else System.identityHashCode(value)

        override fun toString(): String =
                if (value === null) "null"
                else {
                    "${value.javaClass.name}@${System.identityHashCode(value)}"
                }
    }

    sealed class JvmObject {
        data class JvmArray(val elements: List<JvmRef>) : JvmObject()
        data class JvmList(val elements: List<JvmRef>) : JvmObject()
        data class JvmMap(val elements: List<Pair<JvmRef, JvmRef>>) : JvmObject()
        data class JvmDict(val fields: Map<String, JvmRef>) : JvmObject()
    }

    /**
     * Get all superclasses of a class.
     */
    private fun <T> getSuperclasses(cls: Class<T>): MutableList<Class<*>> {
        val result = mutableListOf<Class<*>>()
        var current = cls.superclass
        while (current != null) {
            result.add(current)
            try {
                current = current.superclass
            } catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                break
            }
        }
        return result
    }

    /**
     * Get all fields of a class, including private fields
     * and fields on superclasses.
     */
    private fun <T> getInstanceFields(cls: Class<T>): List<Field> {
        val classes = mutableListOf<Class<*>>()
        try {
            classes.addAll(getSuperclasses(cls))
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
        }
        classes.add(cls)

        val result = mutableListOf<Field>()
        for (c in classes) {
            val fields = try { c.declaredFields }
            catch (e: Throwable) {
                if (e is VirtualMachineError) throw e
                // println("$c $e")
                continue
            }

            for (f in fields) {
                if (Modifier.isStatic(f.modifiers)) continue
                try {
                    f.isAccessible = true
                    result.add(f)
                } catch (e : Throwable) {
                    if (e is VirtualMachineError) throw e
                    // Ignore exceptions in setAccessible.
                    // println("$c.$f $e")
                }
            }
        }
        return result
    }

    /**
     * Get all fields of a class, including private fields
     * and fields on superclasses.
     */
    private fun <T> getClassFields(cls: Class<T>): List<Field> {
        val result = mutableListOf<Field>()

        val fields = try { cls.declaredFields }
        catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            // println("$cls $e")
            return emptyList()
        }

        for (f in fields) {
            if (!Modifier.isStatic(f.modifiers)) continue
            try {
                f.isAccessible = true
                result.add(f)
            } catch (e : Throwable) {
                if (e is VirtualMachineError) throw e
                // Ignore exceptions in setAccessible.
            }
        }
        return result
    }

    data class Path(val parts: ConsList<Select>) {
        operator fun plus (select: Select): Path =
                Path(parts.cons(select))
        fun <Z> fold(empty: Z, part: (Select, Z) -> Z): Z =
                parts.foldRight(empty, part)
    }

    sealed class Select {
        data class Static(val clazz: Class<*>, val name: String) : Select() {
            override fun toString(): String = "${clazz.name}.$name"
        }
        data class Field(val clazz: Class<*>, val name: String) : Select() {
            override fun toString(): String = "${clazz.name}::$name"
        }
        data class Index(val index: Int) : Select() {
            override fun toString(): String = "[$index]"
        }
    }

    private class SearchState(
            val ignoredClasses: MutableSet<Class<*>> = mutableSetOf(
                    Class::class.java,
                    String::class.java,
                    Logger::class.java,
                    BigDecimal::class.java,
                    BigInteger::class.java
            ),
            val cachedInstanceFields: MutableMap<Class<*>, List<Field>> = mutableMapOf(),
            val visitedStatic: MutableSet<Class<*>> = mutableSetOf(),
            val visited: MutableSet<JvmRef> = mutableSetOf(),
            val queue: ArrayDeque<Pair<JvmRef, Path>> = ArrayDeque()
    ) {
        fun readAndEnqueue(ref: JvmRef, path: Path) {
            if (ref in visited) return
            queue.addLast(ref to path)
        }

        fun enqueueFields(ref: JvmRef, path: Path, static: Boolean) {
            // println("1 $ref")
            if (ref.value === null) return
            // println("2")
            if (ref in visited) return
            // println("3")
            val clazz = ref.value.javaClass
            if (clazz in ignoredClasses) return
            // println("4")

            if (static) {
                if (clazz !in visitedStatic) {
                    visitedStatic.add(clazz)

                    val classDict = jvmClass(clazz)
                    for ((k, f) in classDict.fields) {
                        readAndEnqueue(f, Path(consListOf(Select.Static(clazz, k))))
                    }
                }
            }

            val obj = jvmObject(ref)
            // println("$ref (${clazz}) $obj")

            when (obj) {
                null -> {
                    ignoredClasses.add(clazz)
                }

                is JvmObject.JvmList ->
                    for ((i, a) in obj.elements.withIndex())
                        readAndEnqueue(a, path + Select.Index(i))
                is JvmObject.JvmMap ->
                    for ((i, a) in obj.elements.withIndex()) {
                        readAndEnqueue(a.first, path + Select.Index(i) + Select.Field(Map.Entry::class.java, "key"))
                        readAndEnqueue(a.second, path + Select.Index(i) + Select.Field(Map.Entry::class.java, "value"))
                    }

                is JvmObject.JvmArray ->
                    for ((i, a) in obj.elements.withIndex())
                        readAndEnqueue(a, path + Select.Index(i))
                is JvmObject.JvmDict -> {
                    if (obj.fields.isEmpty())
                        ignoredClasses.add(clazz)

                    for ((k, f) in obj.fields)
                        readAndEnqueue(f, path + Select.Field(clazz, k))
                }
            }
        }

        fun jvmObject(ref: JvmRef): JvmObject? {
            val obj = ref.value
            if (obj == null) return null

            fun objectFallback(): JvmObject? {
                val cached = cachedInstanceFields[obj.javaClass]
                if (cached != null) return null

                val fields = getInstanceFields(obj.javaClass)
                cachedInstanceFields[obj.javaClass] = fields

                return JvmObject.JvmDict(fields
                        .filter { !it.type.isPrimitive }
                        .map { it.name to JvmRef(it.get(obj)) }
                        .toMap())
            }

            when (obj) {
                is Array<*> ->
                    if (!obj.javaClass.componentType.isPrimitive) {
                        return JvmObject.JvmArray(obj.map { JvmRef(it) })
                    } else return null

                is List<*> ->
                    try {
                        return JvmObject.JvmList(obj.map { JvmRef(it) })
                    } catch (e: Throwable) {
                        if (e is VirtualMachineError) throw e
                        return objectFallback()
                    }

                is Map<*, *> ->
                    try {
                        return JvmObject.JvmMap(obj.map { JvmRef(it.key) to JvmRef(it.value) })
                    } catch (e: Throwable) {
                        if (e is VirtualMachineError) throw e
                        return objectFallback()
                    }

                else -> return objectFallback()
            }
        }

        fun jvmClass(cls: Class<*>): JvmObject.JvmDict {
            return JvmObject.JvmDict(getClassFields(cls)
                    .filter { !it.type.isPrimitive }
                    .map { it.name to JvmRef(it.get(null)) }
                    .toMap())
        }
    }

    fun findAll(roots: List<JvmRef>, targetRef: JvmRef): List<Path> {
        val found = mutableListOf<Path>()
        val state = SearchState()

        for (root in roots) {
            state.enqueueFields(root, Path(ConsList.Nil), true)
        }

        while (state.queue.isNotEmpty()) {
            val (ref, path) = state.queue.removeFirst()

            if (ref == targetRef) {
                found.add(path)
            } else {
                state.enqueueFields(ref, path, true)
            }

            state.visited.add(ref)
        }

        return found.toList()
    }

    fun findAllByClassLoader(roots: List<JvmRef>, classLoader: ClassLoader): List<Path> {
        val found = mutableListOf<Path>()
        val state = SearchState()

        for (root in roots) {
            val rootClazz = root.value?.javaClass
            if (rootClazz != null && rootClazz.classLoader == classLoader) {
                state.visitedStatic.add(rootClazz)
            }
            state.enqueueFields(root, Path(ConsList.Nil), true)
        }

        while (state.queue.isNotEmpty()) {
            val (ref, path) = state.queue.removeFirst()

            val refClazz = ref.value?.javaClass
            if (refClazz != null && refClazz.classLoader == classLoader) {
                state.visited.add(ref)
                state.visitedStatic.add(refClazz)
                state.ignoredClasses.add(refClazz)
                found.add(path)
            } else {
                state.enqueueFields(ref, path, true)
            }

            state.visited.add(ref)
        }

        return found.toList()
    }
}
