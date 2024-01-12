package com.example.mylibrary

import android.annotation.SuppressLint
import android.graphics.*
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi
import java.lang.RuntimeException

@RequiresApi(Build.VERSION_CODES.S)
class RenderEffectImageProcessor : ImageProcessor {
    override val name = "RenderEffect"
    private var params: Params? = null
    override fun configureInputAndOutput(inputImage: Bitmap, numberOfOutputImages: Int) {
        params = Params(
            inputImage,
            numberOfOutputImages
        )
    }
    private fun applyEffect(it: Params, renderEffect: RenderEffect, outputIndex: Int): Bitmap {
        it.renderNode.setRenderEffect(renderEffect)
        val renderCanvas = it.renderNode.beginRecording()
        renderCanvas.drawBitmap(it.bitmap, 0f,0f,null)
        it.renderNode.endRecording()



        it.hardwareRenderer.createRenderRequest()
            .setWaitForPresent(true)
            .syncAndDraw()
        val image = it.imageReader.acquireNextImage();
        if (image != null)
        {
            val hardwareBuffer = image.hardwareBuffer ?: throw RuntimeException("No HardwareBuffer")
            it.bitmapOut = Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
                ?: throw RuntimeException("Create Bitmap Failed")
            hardwareBuffer.close()
            image.close()
        }
        return it.bitmapOut
    }
    override fun blur(radius: Float, outputIndex: Int): Bitmap {
        params?.let {
            val downSampleRenderEffect = RenderEffect.createBitmapEffect(it.bitmap,
                Rect(0,0,it.bitmap.width, it.bitmap.height),
                Rect(0,0, it.bitmap.width /4, it.bitmap.height/4))
            val blurRenderEffect = RenderEffect.createBlurEffect(
                radius, radius,
                Shader.TileMode.CLAMP)
            val chainedRenderEffect = RenderEffect.createChainEffect(blurRenderEffect, downSampleRenderEffect)
            return applyEffect(it, chainedRenderEffect, outputIndex)
        }
        throw RuntimeException("Not configured!")
    }
    override fun cleanup() {
        params?.let {
            params = null
            it.imageReader.close()
            it.renderNode.discardDisplayList()
            it.hardwareRenderer.destroy()
        }
    }
    inner class Params(val bitmap: Bitmap, numberOfOutputImages: Int) {
        @SuppressLint("WrongConstant")
        val imageReader = ImageReader.newInstance(
            bitmap.width / 4  , bitmap.height / 4,
            PixelFormat.RGBA_8888, numberOfOutputImages,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val renderNode = RenderNode("RenderEffect")
        val hardwareRenderer = HardwareRenderer()
        lateinit var bitmapOut: Bitmap
        init {
            hardwareRenderer.setSurface(imageReader.surface)
            hardwareRenderer.setContentRoot(renderNode)
            renderNode.setPosition(0, 0, bitmap.width , bitmap.height)
        }
    }
}