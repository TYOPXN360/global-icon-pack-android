package com.richardluo.globalIconPack.iconPack.source

import com.richardluo.globalIconPack.iconPack.model.ClockIconEntry
import com.richardluo.globalIconPack.iconPack.model.IconEntry
import com.richardluo.globalIconPack.utils.unflattenFromString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object IconPackInfoSerializer {
  private const val VERSION = 1

  fun toBytes(info: IconPackInfo): ByteArray =
    ByteArrayOutputStream()
      .also { out ->
        DataOutputStream(out).apply {
          writeInt(VERSION)
          writeList(info.iconBacks) { writeUTF(it) }
          writeList(info.iconUpons) { writeUTF(it) }
          writeList(info.iconMasks) { writeUTF(it) }
          writeFloat(info.iconScale)
          writeList(info.iconEntryMap.entries) { (cn, entry) ->
            writeUTF(cn.flattenToString())
            writeByteArray(entry.toByteArray())
          }
          writeList(info.clockIconEntryMap.entries) { (name, entry) ->
            writeUTF(name)
            writeByteArray(entry.toByteArray())
          }
        }
      }
      .toByteArray()

  fun fromBytes(data: ByteArray): IconPackInfo {
    val info = MutableIconPackInfo()
    DataInputStream(ByteArrayInputStream(data)).use {
      val version = it.readInt()
      if (version != VERSION) throw Exception("Incompatible icon pack cache version: $version")
      val backs = it.readList { readUTF() }
      val upons = it.readList { readUTF() }
      val masks = it.readList { readUTF() }
      val scale = it.readFloat()
      val entries = it.readList {
        val cn = unflattenFromString(readUTF()) ?: throw Exception("Invalid component in cache")
        cn to IconEntry.from(readByteArray())
      }
      val clocks = it.readList {
        val name = readUTF()
        val entry = IconEntry.from(readByteArray()) as? ClockIconEntry
          ?: throw Exception("Invalid clock entry in cache")
        name to entry
      }
      info.iconBacks.addAll(backs)
      info.iconUpons.addAll(upons)
      info.iconMasks.addAll(masks)
      info.iconScale = scale
      info.iconEntryMap.putAll(entries)
      info.clockIconEntryMap.putAll(clocks)
    }
    return info
  }
}

private fun <T> DataOutputStream.writeList(
  value: List<T>,
  writeSingle: DataOutputStream.(T) -> Unit,
) {
  writeInt(value.size)
  value.forEach { writeSingle(it) }
}

private fun <T> DataInputStream.readList(readSingle: DataInputStream.() -> T): List<T> =
  mutableListOf<T>().apply { repeat(readInt()) { add(readSingle()) } }
