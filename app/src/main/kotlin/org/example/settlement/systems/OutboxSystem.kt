package org.example.settlement.systems

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World.Companion.family
import org.example.settlement.components.*

class OutboxSystem : IteratingSystem(
    family { all(ParsedStatusC, CorrelatedStatusC) }
) {

    override fun onTickEntity(entity: Entity) {
        // Remove processed status events
        entity.remove()
    }
}