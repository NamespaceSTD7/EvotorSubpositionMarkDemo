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
import ru.evotor.framework.inventory.ProductItem
import ru.evotor.framework.inventory.ProductQuery
import ru.evotor.framework.receipt.Position
import java.math.BigDecimal

/**
 * Демонстрация ошибки Evotor POS: марка (код маркировки), установленная в ПОДПОЗИЦИЮ
 * набора, вызывает диалог «В приложении Evotor POS произошла ошибка».
 *
 * Воспроизведение:
 *  1) в каталоге: «ДЕМО-НАБОР» (обычный товар), «ДЕМО-НАБОР-БЕЗМАРКИ» (обычный,
 *     контроль) и «ДЕМО-ВОДА» (товар с типом WATER_MARKED);
 *  2) продажа → добавить в чек только «ДЕМО-НАБОР» (воду добавлять НЕ нужно);
 *  3) интеграция находит «ДЕМО-ВОДА» в каталоге программно (ProductQuery) и
 *     превращает «ДЕМО-НАБОР» в набор: подпозиция = позиция из карточки «ДЕМО-ВОДА»
 *     (тип WATER_MARKED от карточки) + setMark(код маркировки) —
 *     -> POS падает с «В приложении Evotor POS произошла ошибка»;
 *  4) контроль: «ДЕМО-НАБОР-БЕЗМАРКИ» — тот же набор, но подпозиция БЕЗ марки —
 *     ошибки нет.
 *
 * Вывод: падение вызывает именно марка в подпозиции.
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

                    val setPosition = changes.findPosition { it.name?.startsWith(PREFIX) == true }
                        ?: run {
                            callback.skip()
                            return
                        }

                    val water = findProductByName(WATER_NAME)
                    if (water == null) {
                        Log.w(TAG, "товар '$WATER_NAME' (WATER_MARKED) не найден в каталоге")
                        callback.skip()
                        return
                    }

                    // «ДЕМО-НАБОР» -> с маркой; «ДЕМО-НАБОР-БЕЗМАРКИ» -> контроль без марки
                    val withMark = setPosition.name?.endsWith(SUFFIX_NO_MARK, ignoreCase = true)?.not() ?: true

                    // подпозиция из карточки «ДЕМО-ВОДА»: тип WATER_MARKED от карточки, цена 0
                    val subBuilder = Position.Builder.newInstance(water, BigDecimal.ONE)
                        .setPrice(BigDecimal.ZERO)
                        .setPriceWithDiscountPosition(BigDecimal.ZERO)
                    val sub = if (withMark) subBuilder.setMark(FAKE_MARK).build() else subBuilder.build()

                    Log.d(
                        TAG,
                        "'${setPosition.name}': подпозиция из карточки '${water.name}' " +
                            "(type = ${water.type}) ${if (withMark) "С маркой" else "без марки"}, " +
                            "productType = ${sub.productType}, mark = ${sub.mark}"
                    )

                    changes.add(
                        PositionEdit(
                            Position.Builder.copyFrom(setPosition)
                                .setSubPositions(listOf(sub))
                                .build()
                        )
                    )

                    callback.onResult(BeforePositionsEditedEventResult(changes, null, null))
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка интеграции", e)
                    callback.skip()
                }
            }
        }

        return processors
    }

    private fun findProductByName(name: String): ProductItem.Product? {
        var result: ProductItem.Product? = null
        ProductQuery().name.equal(name)
            .execute(this)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val item = cursor.getValue()
                    if (item is ProductItem.Product && item.name == name) {
                        result = item
                        break
                    }
                }
            }
        return result
    }

    private fun List<IPositionChange>.findPosition(predicate: (Position) -> Boolean): Position? {
        for (change in this) {
            val position = when (change) {
                is PositionAdd -> change.position
                is PositionEdit -> change.position
                else -> null
            } ?: continue
            if (predicate(position)) {
                return position
            }
        }
        return null
    }

    companion object {
        const val TAG = "SubposMarkDemo"
        const val PREFIX = "ДЕМО-"
        const val SUFFIX_NO_MARK = "-БЕЗМАРКИ"
        const val WATER_NAME = "ДЕМО-ВОДА"

        // любая непустая строка, имитирующая код маркировки
        const val FAKE_MARK = "010460709194394321b6I2cVX9LvdwYFGhVk1pZQrTmN"
    }
}