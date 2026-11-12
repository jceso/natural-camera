package com.example.naturalcam.usb

data class MbrPartition(
    val index: Int,
    val type: Int,
    val startLba: Long,
    val sectorCount: Long
)

object MbrParser {

    fun parse(data: ByteArray): List<MbrPartition> {

        require(data.size >= 512) {
            "Settore MBR incompleto"
        }

        if (
            data[510].toInt() and 0xFF != 0x55 ||
            data[511].toInt() and 0xFF != 0xAA
        ) {
            throw IllegalArgumentException(
                "Firma MBR 55 AA non trovata"
            )
        }

        val partitions = mutableListOf<MbrPartition>()

        for (i in 0 until 4) {

            val offset = 446 + i * 16

            val type = data[offset + 4].toInt() and 0xFF

            val startLba = uint32LE(data, offset + 8)
            val sectorCount = uint32LE(data, offset + 12)

            if (type != 0 && sectorCount > 0) {
                partitions.add(
                    MbrPartition(
                        index = i + 1,
                        type = type,
                        startLba = startLba,
                        sectorCount = sectorCount
                    )
                )
            }
        }

        return partitions
    }

    fun parseFat32BootSector(data: ByteArray): String {

        require(data.size >= 512) {
            "Boot sector incompleto"
        }

        val signature =
            (data[510].toInt() and 0xFF) == 0x55 &&
                    (data[511].toInt() and 0xFF) == 0xAA

        if (!signature) {
            return "Firma boot sector non valida"
        }

        val bytesPerSector = uint16LE(data, 11)
        val sectorsPerCluster = data[13].toInt() and 0xFF
        val reservedSectors = uint16LE(data, 14)
        val fatCount = data[16].toInt() and 0xFF
        val totalSectors = uint32LE(data, 32)
        val sectorsPerFat = uint32LE(data, 36)
        val rootCluster = uint32LE(data, 44)

        return buildString {
            appendLine("Firma boot sector: 55 AA")
            appendLine("Byte per settore: $bytesPerSector")
            appendLine("Settori per cluster: $sectorsPerCluster")
            appendLine("Settori riservati: $reservedSectors")
            appendLine("Numero FAT: $fatCount")
            appendLine("Settori totali: $totalSectors")
            appendLine("Settori per FAT: $sectorsPerFat")
            appendLine("Root cluster: $rootCluster")

            if (bytesPerSector == 512) {
                appendLine("Dimensione settore coerente")
            }
        }
    }

    private fun uint16LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun uint32LE(data: ByteArray, offset: Int): Long {
        return (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)
    }
}