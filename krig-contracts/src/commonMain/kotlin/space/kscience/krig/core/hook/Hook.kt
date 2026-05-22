package space.kscience.krig.core.hook

/**
 * Extension-point marker. Each concrete [Hook] is a singleton object parameterised by its
 * handler signature [H]; consumers subscribe through any [HookRegistry] (for example a typed
 * pipeline builder or a `DeviceHub`), integrations fire registered handlers at the
 * appropriate moment.
 */
public interface Hook<H : Any>
