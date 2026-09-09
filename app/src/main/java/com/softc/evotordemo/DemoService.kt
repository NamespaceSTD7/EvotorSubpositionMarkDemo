package com.softc.evotordemo

import android.util.Log
import ru.evotor.framework.core.IntegrationService
import ru.evotor.framework.core.action.event.receipt.before_positions_edited.BeforePositionsEditedEvent
import ru.evotor.framework.core.action.event.receipt.before_positions_edited.BeforePositionsEditedEventProcessor
import ru.evotor.framework.core.action.event.receipt.before_positions_edited.BeforePositionsEditedEventResult
import ru.evotor.framework.core.action.event.receipt.changes.position.IPositionChange
import ru.evotor.framework.core.action.event.receipt.changes.position.PositionAdd
import ru.evotor.framework.core.action.event.receipt.changes.position.PositionEdit
import ru.evotor.framework.core.action.processor.ActionProcessor
import ru.evotor.framework.receipt.Position
import ru.evotor.framework.receipt.position.Mark
import java.math.BigDecimal
import java.util.UUID

/**
 * Демонстрация ошибки Evotor POS: марка (код маркировки), установленная в ПОДПОЗИЦИЮ
 * набора, вызывает диалог «В приложении Evotor POS произошла ошибка».
 *
 * Воспроизведение:
 *  1) завести в каталоге обычный (NORMAL) товар «ДЕМО-МАРКА»;
 *  2) добавить его в чек;
 *  3) интеграция превращает позицию в «набор» и возвращает её через PositionEdit:
 *       - подпозиция 1 — обычный товар (NORMAL);
 *       - подпозиция 2 — принудительно WATER_MARKED С маркой Mark.RawMark
 *         -> POS падает с «В приложении Evotor POS произошла ошибка».
 *
 * Сравнение: товар «ДЕМО-БЕЗМАРКИ» строит набор с подпозицией БЕЗ марки —
 * ошибка не возникает. То есть падение вызывается именно наличием марки в подпозиции.
 */
class DemoService : IntegrationService() {

    override fun createProcessors(): Map<String, ActionProcessor>? {
        val processors = HashMap<String, ActionProcessor>()

        processors[BeforePositionsEditedEvent.NAME_SELL_RECEIPT] = object : BeforePositionsEditedEventProcessor() {
            override fun call(
                action: String,
                event: BeforePositionsEditedEvent,
                callback: ActionProcessor.Callback
            ) {
                try {
                    val changes = ArrayList(event.changes)

                    if (changes.none { it.getPosition()?.name?.startsWith(PREFIX) == true }) {
                        callback.skip()
                        return
                    }

                    for (change in ArrayList(changes)) {
                        val position = change.getPosition() ?: continue
                        if (!position.name.startsWith(PREFIX)) {
                            continue
                        }
                        changes.add(PositionEdit(buildSet(position)))
                    }

                    callback.onResult(BeforePositionsEditedEventResult(changes, null, null))
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка интеграции", e)
                    callback.skip()
                }
            }
        }

        return processors
    }

    private fun IPositionChange.getPosition(): Position? {
        return when (this) {
            is PositionAdd -> position
            is PositionEdit -> position
            else -> null
        }
    }

    private fun buildSet(added: Position): Position {
        // подпозиция 1 — обычный товар (без маркировки)
        val subNormal = Position.Builder.copyFrom(added)
            .setUuid(UUID.randomUUID().toString())
            .setQuantity(BigDecimal.ONE)
            .build()

        // подпозиция 2 — маркированный товар (вода)
        val withMark = added.name.endsWith(SUFFIX_NO_MARK, ignoreCase = true).not()
        val subMarked = if (withMark) {
            Position.Builder.copyFrom(added)
                .setUuid(UUID.randomUUID().toString())
                .setQuantity(BigDecimal.ONE)
                .toWaterMarked(Mark.RawMark(FAKE_MARK))
                .build()
        } else {
            // контроль: подпозиция БЕЗ марки (обычный товар) — ошибки нет
            Position.Builder.copyFrom(added)
                .setUuid(UUID.randomUUID().toString())
                .setQuantity(BigDecimal.ONE)
                .build()
        }

        Log.d(
            TAG,
            "товар '${added.name}': подпозиция WATER_MARKED ${if (withMark) "С маркой" else "без марки"} " +
                "(mark = ${subMarked.mark}, productType = ${subMarked.productType})"
        )

        return Position.Builder.copyFrom(added)
            .setSubPositions(listOf(subNormal, subMarked))
            .build()
    }

    companion object {
        const val TAG = "SubposMarkDemo"
        const val PREFIX = "ДЕМО-"
        const val SUFFIX_NO_MARK = "-БЕЗМАРКИ"

        // любая непустая строка, имитирующая код маркировки
        const val FAKE_MARK = "010460709194394321b6I2cVX9LvdwYFGhVk1pZQrTmN"
    }
}