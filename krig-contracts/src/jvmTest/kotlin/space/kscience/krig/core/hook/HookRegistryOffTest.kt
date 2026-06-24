package space.kscience.krig.core.hook

import kotlin.test.Test
import kotlin.test.assertEquals

class HookRegistryOffTest {

    private object TestHook : Hook<(Int) -> Unit>

    @Test
    fun offRemovesTheHandler() {
        val registry = HookRegistry.buffered()
        val seen = mutableListOf<Int>()
        val handler: (Int) -> Unit = { seen += it }
        registry.register(TestHook, handler)
        registry.handlersOf(TestHook).forEach { it(1) }
        registry.off(TestHook, handler)
        registry.handlersOf(TestHook).forEach { it(2) }
        assertEquals(listOf(1), seen)
        assertEquals(true, registry.isEmpty())
    }

    @Test
    fun registerReturnsClosableHandle() {
        val registry = HookRegistry.buffered()
        val seen = mutableListOf<Int>()
        val registration = registry.register(TestHook) { seen += it }
        registry.handlersOf(TestHook).forEach { it(7) }
        registration.close()
        registry.handlersOf(TestHook).forEach { it(9) }
        assertEquals(listOf(7), seen)
        assertEquals(true, registry.isEmpty())
    }

    @Test
    fun offIsNoOpIfHandlerMissing() {
        val registry = HookRegistry.buffered()
        val handler: (Int) -> Unit = { }
        // No prior on() — off must be a no-op.
        registry.off(TestHook, handler)
        assertEquals(true, registry.isEmpty())
    }

    @Test
    fun offKeepsOtherHandlersForTheSameHook() {
        val registry = HookRegistry.buffered()
        val a: (Int) -> Unit = { }
        val b: (Int) -> Unit = { }
        val c: (Int) -> Unit = { }
        registry.register(TestHook, a)
        registry.register(TestHook, b)
        registry.register(TestHook, c)
        registry.off(TestHook, b)
        assertEquals(listOf(a, c), registry.handlersOf(TestHook))
    }
}
