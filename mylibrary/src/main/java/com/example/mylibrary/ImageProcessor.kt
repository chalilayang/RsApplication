package com.example.mylibrary

import android.graphics.Bitmap
interface ImageProcessor {
    val name: String
    fun configureInputAndOutput(inputImage: Bitmap, numberOfOutputImages: Int)
    fun blur(radius: Float, outputIndex: Int): Bitmap
    fun cleanup()
    companion object {
        private fun rescale(progress: Int, min: Double, max: Double): Double {
            return (max - min) * (progress / 100.0) + min
        }
        fun runFilter(processor: ImageProcessor, progress: Int): Bitmap {
            val radius = rescale(progress, 1.0, 50.0)
            return  processor.blur(radius.toFloat(), 0)
        }
    }
}