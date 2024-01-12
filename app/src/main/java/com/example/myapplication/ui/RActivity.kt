
package com.example.myapplication.ui

import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import com.example.mylibrary.ImageProcessor
import com.example.mylibrary.NormalImageProcessor
import com.example.mylibrary.RenderEffectImageProcessor
import kotlin.system.measureNanoTime


class RActivity : AppCompatActivity() {
    companion object {
        private val TAG = RActivity::class.java.simpleName


        private const val NUMBER_OF_OUTPUT_IMAGES = 2


        private const val MAX_WARMUP_ITERATIONS = 10


        private const val MAX_WARMUP_TIME_MS = 1000.0


        private const val MAX_BENCHMARK_ITERATIONS = 1000


        private const val MAX_BENCHMARK_TIME_MS = 5000.0
    }

    private lateinit var mLatencyTextView: TextView
    private lateinit var mImageView: ImageView
    private lateinit var mSeekBar: SeekBar
    private lateinit var mProcessorSpinner: Spinner
    private lateinit var mFilterSpinner: Spinner
    private lateinit var mBenchmarkButton: Button


    private lateinit var mInputImage: Bitmap
    private var mCurrentOutputImageIndex = 0


    private lateinit var mImageProcessors: Array<ImageProcessor>
    private lateinit var mCurrentProcessor: ImageProcessor

    enum class FilterMode {
        BLUR
    }

    private var mFilterMode = FilterMode.BLUR

    private var mLatestThread: Thread? = null
    private val mLock = Any()

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        mInputImage = loadBitmap(R.drawable.data)


        val imageProcessors = mutableListOf(
            RenderEffectImageProcessor(),
            NormalImageProcessor()
        )

        mImageProcessors = imageProcessors.toTypedArray()

        mImageProcessors.forEach { processor ->
            processor.configureInputAndOutput(mInputImage, NUMBER_OF_OUTPUT_IMAGES)
        }
        mCurrentProcessor = mImageProcessors[0]


        mImageView = findViewById(R.id.imageView)
        mLatencyTextView = findViewById(R.id.latencyText)


        mSeekBar = findViewById(R.id.seekBar)
        mSeekBar.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                startUpdateImage(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        mSeekBar.progress = 50

        mProcessorSpinner = findViewById(R.id.processorSpinner)
        ArrayAdapter.createFromResource(
            this,
            R.array.processor_array,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            mProcessorSpinner.adapter = adapter
        }
        mProcessorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                mCurrentProcessor = mImageProcessors[position]
                startUpdateImage(mSeekBar.progress)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }


        mFilterSpinner = findViewById(R.id.filterSpinner)
        ArrayAdapter.createFromResource(
            this,
            R.array.filter_array,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            mFilterSpinner.adapter = adapter
        }
        mFilterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                mFilterMode = FilterMode.values()[position]
                startUpdateImage(mSeekBar.progress)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }


        mBenchmarkButton = findViewById(R.id.benchmarkButton)
        mBenchmarkButton.setOnClickListener { startBenchmark() }
    }

    override fun onDestroy() {
        mImageProcessors.forEach { processor -> processor.cleanup() }
        super.onDestroy()
    }

    // sgq: 模拟load为GPU Bitmap
    @RequiresApi(Build.VERSION_CODES.O)
    private fun loadBitmap(resource: Int): Bitmap {
        val options = BitmapFactory.Options()
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        options.inScaled = false
        //options.inSampleSize = 4
        options.inPreferredConfig = Config.HARDWARE;  //硬件bitmap
        return BitmapFactory.decodeResource(resources, resource, options)
            ?: throw RuntimeException("Unable to load bitmap.")
    }

    private fun rescale(progress: Int, min: Double, max: Double): Double {
        return (max - min) * (progress / 100.0) + min
    }

    private fun runFilter(processor: ImageProcessor, filter: FilterMode, progress: Int): Bitmap {
        return when (filter) {
            FilterMode.BLUR -> {
                val radius = rescale(progress, 1.0, 50.0)
                processor.blur(radius.toFloat(), mCurrentOutputImageIndex)
            }
        }
    }


    private fun startUpdateImage(progress: Int) {
        val filterMode = mFilterMode
        val processor = mCurrentProcessor


        mLatestThread = Thread(Runnable {
            synchronized(mLock) {

                if (mLatestThread != Thread.currentThread()) {
                    return@Runnable
                }


                lateinit var bitmapOut: Bitmap
                val duration = measureNanoTime {
                    bitmapOut = runFilter(processor, filterMode, progress)
                }


                this@RActivity.runOnUiThread {
                    mImageView.setImageBitmap(bitmapOut)
                    mLatencyTextView.text = getString(R.string.latency_text, duration / 1000000.0)
                }
                mCurrentOutputImageIndex = (mCurrentOutputImageIndex + 1) % NUMBER_OF_OUTPUT_IMAGES
            }
        })
        mLatestThread?.start()
    }


    private fun runBenchmark(processor: ImageProcessor, filter: FilterMode, progress: Int): Double {

        val runBenchmarkLoop = {maxIterations: Int, maxTimeMs: Double ->
            var iterations = 0
            var totalTime = 0.0
            while (iterations < maxIterations && totalTime < maxTimeMs) {
                iterations += 1
                totalTime += measureNanoTime { runFilter(processor, filter, progress) } / 1000000.0
            }
            totalTime / iterations
        }


        runBenchmarkLoop(MAX_WARMUP_ITERATIONS, MAX_WARMUP_TIME_MS)


        val avgMs = runBenchmarkLoop(MAX_BENCHMARK_ITERATIONS, MAX_BENCHMARK_TIME_MS)


        val processorName = processor.name
        val filterName = filter.toString()
        Log.i(
            TAG,
            "Benchmark result: filter = ${filterName}, progress = ${progress}, " +
            "processor = ${processorName}, avg_ms = ${avgMs}"
        )
        return avgMs
    }


    private fun startBenchmark() {

        mSeekBar.isEnabled = false
        mProcessorSpinner.isEnabled = false
        mFilterSpinner.isEnabled = false
        mBenchmarkButton.isEnabled = false
        mLatencyTextView.setText(R.string.benchmark_running_text)


        Thread {
            synchronized(mLock) {

                val avgMs = runBenchmark(mCurrentProcessor, mFilterMode, mSeekBar.progress)


                this@RActivity.runOnUiThread {
                    mLatencyTextView.text = getString(R.string.benchmark_result_text, avgMs)
                    mSeekBar.isEnabled = true
                    mProcessorSpinner.isEnabled = true
                    mFilterSpinner.isEnabled = true
                    mBenchmarkButton.isEnabled = true
                }
            }
        }.start()
    }
}
