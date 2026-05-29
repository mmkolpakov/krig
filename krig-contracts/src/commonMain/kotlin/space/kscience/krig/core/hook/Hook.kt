package space.kscience.krig.core.hook

/**
 * Typed extension point key. Each concrete [Hook] is a singleton object parameterised
 * by handler signature [H]. Hooks are intended for infrastructure layers such as
 * pipelines and hubs; ordinary device behavior should use contracts, backends, and
 * state models instead.
 */
public interface Hook<H : Any>
