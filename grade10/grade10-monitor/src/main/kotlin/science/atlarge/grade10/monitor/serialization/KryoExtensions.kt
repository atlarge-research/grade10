package science.atlarge.grade10.monitor.serialization

import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output

fun Output.writeDeltaLongs(longs: LongArray) {
    if (longs.isEmpty()) return

    writeLong(longs[0])
    for (i in 1 until longs.size) writeVarLong(longs[i] - longs[i - 1], true)
}

fun Input.readDeltaLongs(count: Int): LongArray {
    if (count <= 0) return LongArray(0)

    val longs = LongArray(count)
    longs[0] = readLong()
    for (i in 1 until longs.size) longs[i] = longs[i - 1] + readVarLong(true)

    return longs
}