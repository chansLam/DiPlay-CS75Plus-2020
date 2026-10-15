package com.shihab.diplay.orchestration

/**
 * Thin facade over [CarPlayController] documenting the reusable session entry points that hosts
 * and UI layers call: start, stop ([close]), and reconnect.
 *
 * Prefer this when wiring new call sites. Existing hosts may keep using [CarPlayController]
 * directly; this type is a documented alias of the same lifecycle surface, not a parallel stack.
 */
class CarPlaySessionFacade(private val controller: CarPlayController) {

    /** Begins MFi / transport bring-up for the configured session. */
    fun start() = controller.start()

    /** Tears down the session (same as [CarPlayController.close]). */
    fun stop() = controller.close()

    /** Reopens the MFi path without restarting the process. */
    fun reconnectMfi() = controller.reconnectMfi()

    /** Re-runs iPhone discovery / wireless bring-up on the open MFi session. */
    fun reconnectIphone() = controller.reconnectIphone()

    val controllerRef: CarPlayController get() = controller
}

/** Documented entry points on [CarPlayController] for hosts that keep a direct reference. */
typealias CarPlaySession = CarPlayController
