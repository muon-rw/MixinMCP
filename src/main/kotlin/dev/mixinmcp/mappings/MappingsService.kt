package dev.mixinmcp.mappings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.fabricmc.mappingio.tree.MemoryMappingTree
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
class MappingsService {

    private val cache = ConcurrentHashMap<Key, MemoryMappingTree>()
    private val locks = ConcurrentHashMap<Key, Mutex>()

    suspend fun get(mcVersion: String, required: Set<MappingNamespace>): MemoryMappingTree {
        val key = Key(mcVersion, required)
        cache[key]?.let { return it }
        val lock = locks.computeIfAbsent(key) { Mutex() }
        return lock.withLock {
            cache[key]?.let { return@withLock it }
            val tree = MappingsLoader.load(mcVersion, required)
            cache[key] = tree
            tree
        }
    }

    data class Key(val mcVersion: String, val required: Set<MappingNamespace>)

    companion object {
        fun getInstance(): MappingsService =
            ApplicationManager.getApplication().getService(MappingsService::class.java)
    }
}
