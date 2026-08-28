package com.agent.ai

import android.app.Application
import com.agent.ai.data.memory.AgentMemoryHub
import com.agent.ai.service.AgentServiceStarter
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AIAgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AgentMemoryHub.init(this)
        AgentServiceStarter.startIfReady(this)
    }
}
