package dev.lingoflow.shared.domain.doc

import dev.lingoflow.shared.core.util.SimpleDate

/** Date labels for the UI (kept out of screens so both platforms agree). */
object SimpleDateLabel {
    fun format(epochMillis: Long): String = SimpleDate.format(epochMillis)
}
