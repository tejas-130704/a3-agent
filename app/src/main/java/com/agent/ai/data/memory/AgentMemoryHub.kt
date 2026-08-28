package com.agent.ai.data.memory

import android.content.Context

/** Global access to session + long-term memory (initialized in [com.agent.ai.AIAgentApp]). */
object AgentMemoryHub {
    lateinit var repository: AgentMemoryRepository
        private set
    lateinit var extractor: MemoryExtractor
        private set
    val session = SessionContext()

    fun init(context: Context) {
        if (::repository.isInitialized) return
        repository = AgentMemoryRepository(context.applicationContext)
        extractor = MemoryExtractor(repository)
    }

    fun isReady(): Boolean = ::repository.isInitialized
}
