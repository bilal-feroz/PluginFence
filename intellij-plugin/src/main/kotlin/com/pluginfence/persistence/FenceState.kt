package com.pluginfence.persistence

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection

/**
 * Durable configuration and learned knowledge: settings, per-plugin policies, behaviour
 * baselines and drift reports. Stored locally only (roaming disabled) - PluginFence never
 * uploads anything.
 */
@State(name = "PluginFence", storages = [Storage("pluginfence.xml", roamingType = RoamingType.DISABLED)])
class FenceState : PersistentStateComponent<FenceState.State> {

    @Tag("settings")
    class Settings {
        var enforcementEnabled: Boolean = true
        var notifyHighRisk: Boolean = true
        var maxEvents: Int = 500
        var maxIncidents: Int = 100
    }

    class State {
        var settings: Settings = Settings()
        @XCollection(style = XCollection.Style.v2)
        var policies: MutableList<PolicyBean> = mutableListOf()
        @XCollection(style = XCollection.Style.v2)
        var profiles: MutableList<ProfileBean> = mutableListOf()
        @XCollection(style = XCollection.Style.v2)
        var drifts: MutableList<DriftBean> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): FenceState = ApplicationManager.getApplication().getService(FenceState::class.java)
    }
}

/** Bounded recent history: events and incidents. Separate file so it can be cleared independently. */
@State(name = "PluginFenceHistory", storages = [Storage("pluginfence-history.xml", roamingType = RoamingType.DISABLED)])
class FenceHistory : PersistentStateComponent<FenceHistory.State> {

    class State {
        @XCollection(style = XCollection.Style.v2)
        var events: MutableList<EventBean> = mutableListOf()
        @XCollection(style = XCollection.Style.v2)
        var incidents: MutableList<IncidentBean> = mutableListOf()
        var lastEventId: Long = 0
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): FenceHistory = ApplicationManager.getApplication().getService(FenceHistory::class.java)
    }
}
