package com.pluginfence.engine

import com.intellij.util.messages.Topic
import com.pluginfence.model.BehaviorDrift
import com.pluginfence.model.FenceEvent
import com.pluginfence.model.Incident

/**
 * Application message-bus topic the UI subscribes to. Callbacks arrive on a background thread
 * and are coalesced by the UI; they carry no payload the UI cannot re-read from [FenceEngine].
 */
interface FenceListener {

    /** New events were recorded (batched). */
    fun eventsRecorded(events: List<FenceEvent>) {}

    fun incidentUpdated(incident: Incident) {}

    fun driftUpdated(drift: BehaviorDrift) {}

    /** Policies, settings, baselines or history were changed by the user. */
    fun stateChanged() {}

    companion object {
        @JvmField
        val TOPIC: Topic<FenceListener> = Topic.create("PluginFence events", FenceListener::class.java)
    }
}
