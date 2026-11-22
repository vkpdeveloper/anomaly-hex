package ly.anoma.hex.keyboard

import ly.anoma.hex.typing.KeyPoint
import ly.anoma.hex.typing.KeyRect

/** The neighbouring letters a touch could be read as ([KeyboardEngine.likelierLetter]): each key whose
 * touch area ([areas]) is within [reach] of [touch], with that distance. None when the touch is on its
 * own key's drawn keycap ([face]): a tap on a key always types that key, and only a touch in the gap
 * between keycaps may go to a neighbour. One coordinate space throughout. */
internal fun nearKeys(touch: KeyPoint, face: KeyRect, areas: Map<Char, KeyRect>, reach: Double): Map<Char, Double> {
    if (touch.x in face.left..face.right && touch.y in face.top..face.bottom) return emptyMap()
    val near = HashMap<Char, Double>()
    for ((c, area) in areas) {
        val dx = maxOf(area.left - touch.x, 0.0, touch.x - area.right)
        val dy = maxOf(area.top - touch.y, 0.0, touch.y - area.bottom)
        val distance = Math.hypot(dx, dy)
        if (distance < reach) near[c] = distance
    }
    return near
}
