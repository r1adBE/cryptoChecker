package com.cryptochecker.app.tts

import android.content.Context
import com.cryptochecker.app.R
import com.cryptochecker.app.data.local.model.AlarmCondition
import com.cryptochecker.app.data.local.model.WatchEntity
import com.cryptochecker.app.util.PriceFormat
import com.cryptochecker.marketdata.config.MarketsConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Baut die Sätze für die Sprachausgabe. */
@Singleton
class SpokenText @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun price(watch: WatchEntity, price: Double): String =
        context.getString(
            R.string.tts_price,
            marketName(watch),
            watch.baseAsset,
            watch.quoteAsset,
            PriceFormat.spokenPrice(price)
        )

    fun alarm(watch: WatchEntity, condition: AlarmCondition, price: Double): String {
        val direction = context.getString(
            when (condition) {
                AlarmCondition.PRICE_ABOVE, AlarmCondition.CHANGE_PERCENT_UP -> R.string.tts_direction_up
                AlarmCondition.PRICE_BELOW, AlarmCondition.CHANGE_PERCENT_DOWN -> R.string.tts_direction_down
                AlarmCondition.MOVE_PERCENT_WINDOW -> R.string.tts_direction_move
                AlarmCondition.VOLUME_SPIKE -> R.string.tts_direction_volume_spike
                AlarmCondition.NEAR_HIGH -> R.string.tts_direction_near_high
                AlarmCondition.NEAR_LOW -> R.string.tts_direction_near_low
                AlarmCondition.FUNDING_ABOVE -> R.string.tts_direction_funding_above
                AlarmCondition.FUNDING_BELOW -> R.string.tts_direction_funding_below
                AlarmCondition.OI_UP -> R.string.tts_direction_oi_up
                AlarmCondition.OI_DOWN -> R.string.tts_direction_oi_down
            }
        )
        return context.getString(
            R.string.tts_alarm,
            marketName(watch),
            watch.baseAsset,
            watch.quoteAsset,
            direction,
            PriceFormat.spokenPrice(price)
        )
    }

    /** Börsen liefern einen aussprechbaren Namen mit (z. B. "MCX now"). */
    private fun marketName(watch: WatchEntity): String =
        MarketsConfig.MARKETS[watch.marketKey]?.ttsName ?: watch.marketName
}
