package pro.noodle.mobile.core.player

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/** Пока пользователь открывает видео, фоновые сканы (колокольчик, обслуживание) ждут: сеть и CPU — приоритет плееру. */
object BackgroundGate {
    private val active = AtomicInteger(0)
    @Volatile private var lastEndMs = 0L

    fun enter() { active.incrementAndGet() }
    fun leave() { active.updateAndGet { (it - 1).coerceAtLeast(0) }; lastEndMs = System.currentTimeMillis() }

    suspend fun awaitIdle(maxWaitMs: Long = 180_000L) {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < maxWaitMs && (active.get() > 0 || System.currentTimeMillis() - lastEndMs < 4_000L)) delay(500)
    }
}
