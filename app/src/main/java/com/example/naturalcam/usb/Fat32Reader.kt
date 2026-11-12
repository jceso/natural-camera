package com.example.naturalcam.usb

import java.io.IOException

data class Fat32File(
    val name: String,
    val firstCluster: Long,
    val size: Long,
    val isDirectory: Boolean = false,
    val relativePath: String = ""
) {
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()

    val isImage: Boolean
        get() = extension in setOf("jpg", "jpeg", "png", "bmp", "gif", "webp")

    val isVideo: Boolean
        get() = extension in setOf("avi", "mp4", "mov", "mkv", "3gp", "mpeg", "mpg")
}

class Fat32Reader(
    private val transport: MassStorageTransport,
    private val partitionStartLba: Long
) {
    private val sectorSize = 512
    private val fatSectorCache = mutableMapOf<Long, ByteArray>()

    private var sectorsPerCluster = 0
    private var reservedSectors = 0L
    private var fatCount = 0
    private var sectorsPerFat = 0L
    private var rootCluster = 0L
    private var dataStartLba = 0L

    init {
        readBootSector()
    }

    private fun readSectors(lba: Long, count: Int): ByteArray {
        val result = transport.readSectors(lba, count)

        if (!result.successful) {
            throw IOException("READ(10) fallito su LBA $lba")
        }

        val expected = count * sectorSize

        if (result.data.size != expected) {
            throw IOException(
                "Lettura incompleta su LBA $lba: ${result.data.size}/$expected byte"
            )
        }

        return result.data
    }

    private fun readBootSector() {
        val boot = readSectors(partitionStartLba, 1)

        if (u8(boot, 510) != 0x55 || u8(boot, 511) != 0xAA) {
            throw IOException("Boot sector FAT32 non valido")
        }

        val bytesPerSector = u16(boot, 11)
        sectorsPerCluster = u8(boot, 13)
        reservedSectors = u16(boot, 14).toLong()
        fatCount = u8(boot, 16)
        sectorsPerFat = u32(boot, 36)
        rootCluster = u32(boot, 44)

        if (bytesPerSector != sectorSize) {
            throw IOException("Dimensione settore non supportata")
        }

        if (sectorsPerCluster <= 0 ||
            sectorsPerCluster and (sectorsPerCluster - 1) != 0
        ) {
            throw IOException("Dimensione cluster FAT32 non valida")
        }

        if (fatCount <= 0 || sectorsPerFat <= 0 || rootCluster < 2) {
            throw IOException("Parametri FAT32 non validi")
        }

        dataStartLba = partitionStartLba +
                reservedSectors +
                fatCount * sectorsPerFat
    }

    private fun readCluster(cluster: Long): ByteArray {
        if (cluster < 2) {
            throw IOException("Cluster FAT32 non valido: $cluster")
        }

        val lba = dataStartLba +
                (cluster - 2) * sectorsPerCluster

        return readSectors(lba, sectorsPerCluster)
    }

    private fun nextCluster(cluster: Long): Long {
        val fatOffset = cluster * 4
        val fatSector = fatOffset / sectorSize
        val entryOffset = (fatOffset % sectorSize).toInt()

        if (fatSector >= sectorsPerFat) {
            throw IOException("Indice FAT fuori intervallo")
        }

        val absoluteFatLba =
            partitionStartLba + reservedSectors + fatSector

        val sector = fatSectorCache.getOrPut(absoluteFatLba) {
            readSectors(absoluteFatLba, 1)
        }

        return u32(sector, entryOffset) and 0x0FFFFFFF
    }

    private fun clusterChain(firstCluster: Long): Sequence<Long> = sequence {
        var current = firstCluster
        val visited = mutableSetOf<Long>()

        while (current in 2..0x0FFFFFEF) {
            if (!visited.add(current)) {
                throw IOException("Loop rilevato nella catena FAT")
            }

            if (visited.size > 100_000) {
                throw IOException("Catena FAT troppo lunga")
            }

            yield(current)

            val next = nextCluster(current)

            if (next >= 0x0FFFFFF8) break

            if (next == 0L || next == 1L || next == 0x0FFFFFF7L ||
                next >= 0x0FFFFFF0L
            ) {
                throw IOException("Catena FAT non valida")
            }

            current = next
        }
    }

    private fun readDirectory(firstCluster: Long): List<Fat32File> {
        val entries = mutableListOf<Fat32File>()
        val lfnParts = mutableMapOf<Int, List<Int>>()

        for (cluster in clusterChain(firstCluster)) {
            val chunk = readCluster(cluster)
            var offset = 0

            while (offset + 32 <= chunk.size) {
                val firstByte = u8(chunk, offset)

                if (firstByte == 0x00) return entries

                if (firstByte == 0xE5) {
                    lfnParts.clear()
                    offset += 32
                    continue
                }

                val attributes = u8(chunk, offset + 11)

                if (attributes == 0x0F) {
                    val sequence = firstByte and 0x1F

                    if (firstByte and 0x40 != 0) {
                        lfnParts.clear()
                    }

                    if (sequence > 0) {
                        lfnParts[sequence] = readLfnFragment(chunk, offset)
                    }
                } else {
                    val shortName = readShortName(chunk, offset)

                    val longName = if (lfnParts.isNotEmpty()) {
                        lfnParts.toSortedMap()
                            .values
                            .flatten()
                            .takeWhile { it != 0x0000 && it != 0xFFFF }
                            .map { it.toChar() }
                            .joinToString("")
                            .ifBlank { shortName }
                    } else {
                        shortName
                    }

                    lfnParts.clear()

                    val isDirectory = attributes and 0x10 != 0
                    val isVolumeLabel = attributes and 0x08 != 0

                    if (!isVolumeLabel &&
                        longName != "." &&
                        longName != ".."
                    ) {
                        val high = u16(chunk, offset + 20).toLong()
                        val low = u16(chunk, offset + 26).toLong()
                        val first = (high shl 16) or low
                        val size = u32(chunk, offset + 28)

                        entries.add(
                            Fat32File(
                                name = longName,
                                firstCluster = first,
                                size = size,
                                isDirectory = isDirectory
                            )
                        )
                    }
                }

                offset += 32
            }
        }

        return entries
    }

    /**
     * Restituisce tutte le fotografie e i video trovati,
     * attraversando ricorsivamente le directory.
     */
    fun listMedia(): List<Fat32File> {
        val media = mutableListOf<Fat32File>()
        val visitedDirectories = mutableSetOf<Long>()

        fun scanDirectory(cluster: Long, parentPath: String, depth: Int) {
            if (depth > 20) return
            if (cluster < 2) return
            if (!visitedDirectories.add(cluster)) return

            val entries = readDirectory(cluster)

            for (entry in entries) {
                val path = if (parentPath.isEmpty()) {
                    entry.name
                } else {
                    "$parentPath/${entry.name}"
                }

                if (entry.isDirectory) {
                    scanDirectory(
                        entry.firstCluster,
                        path,
                        depth + 1
                    )
                } else if (entry.isImage || entry.isVideo) {
                    media.add(
                        entry.copy(relativePath = path)
                    )
                }
            }
        }

        scanDirectory(rootCluster, "", 0)

        return media.sortedWith(
            compareBy<Fat32File>(
                { it.relativePath.lowercase() }
            )
        )
    }

    /**
     * Compatibilità con il vecchio browser PHOTO.
     */
    fun listPhotos(folderName: String = "PHOTO"): List<Fat32File> {
        return listMedia().filter {
            it.isImage &&
                    it.relativePath.substringBefore("/")
                        .equals(folderName, ignoreCase = true)
        }
    }

    fun readFile(
        file: Fat32File,
        maxSize: Long = 50L * 1024 * 1024
    ): ByteArray {
        if (file.size > maxSize || file.size > Int.MAX_VALUE) {
            throw IOException("File troppo grande: ${file.size} byte")
        }

        if (file.size == 0L) return ByteArray(0)

        if (file.firstCluster < 2) {
            throw IOException("Cluster iniziale del file non valido")
        }

        val output = ByteArray(file.size.toInt())
        var position = 0

        for (cluster in clusterChain(file.firstCluster)) {
            val data = readCluster(cluster)
            val remaining = output.size - position

            if (remaining <= 0) break

            val count = minOf(data.size, remaining)

            System.arraycopy(data, 0, output, position, count)
            position += count
        }

        if (position != output.size) {
            throw IOException(
                "File incompleto: $position/${output.size} byte"
            )
        }

        return output
    }

    private fun readShortName(data: ByteArray, offset: Int): String {
        val base = String(data, offset, 8, Charsets.US_ASCII).trim()
        val extension = String(data, offset + 8, 3, Charsets.US_ASCII).trim()

        return if (extension.isEmpty()) base else "$base.$extension"
    }

    private fun readLfnFragment(data: ByteArray, offset: Int): List<Int> {
        val positions = intArrayOf(
            1, 3, 5, 7, 9,
            14, 16, 18, 20, 22, 24,
            28, 30
        )

        return positions.map { position ->
            u16(data, offset + position)
        }
    }

    private fun u8(data: ByteArray, offset: Int): Int =
        data[offset].toInt() and 0xFF

    private fun u16(data: ByteArray, offset: Int): Int =
        u8(data, offset) or (u8(data, offset + 1) shl 8)

    private fun u32(data: ByteArray, offset: Int): Long =
        u8(data, offset).toLong() or
                (u8(data, offset + 1).toLong() shl 8) or
                (u8(data, offset + 2).toLong() shl 16) or
                (u8(data, offset + 3).toLong() shl 24)
}