package com.canon.cr3transfer.data.exif

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifSubIFDDirectory
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExifReader @Inject constructor() {

    data class ExifData(
        val iso: Int?,
        val aperture: String?,
        val shutterSpeed: String?,
    )

    fun read(file: File): ExifData {
        return try {
            val metadata = ImageMetadataReader.readMetadata(file)
            val exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
            ExifData(
                iso = exif?.getInteger(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT),
                aperture = exif?.getString(ExifSubIFDDirectory.TAG_FNUMBER),
                shutterSpeed = exif?.getString(ExifSubIFDDirectory.TAG_EXPOSURE_TIME),
            )
        } catch (_: Exception) {
            ExifData(null, null, null)
        }
    }
}
