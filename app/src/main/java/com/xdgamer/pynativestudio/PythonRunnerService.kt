package com.xdgamer.pynativestudio

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** Runs CPython in a dedicated Android process so hard-stop is reliable. */
class PythonRunnerService : Service() {
    fun interface OutputCallback {
        fun call(stream: String, text: String)
    }

    fun interface InputCallback {
        fun call(): String
    }

    private val inputQueue = LinkedBlockingQueue<String>()

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_RUN -> {
                if (!running) {
                    runCode(
                        code = intent.getStringExtra(EXTRA_CODE).orEmpty(),
                        name = intent.getStringExtra(EXTRA_NAME)
                            ?: "untitled.py"
                    )
                }
            }

            ACTION_INPUT -> {
                inputQueue.offer(
                    intent.getStringExtra(EXTRA_INPUT).orEmpty()
                )
            }

            ACTION_STOP -> {
                broadcast(EVENT_FINISHED, "Stopped", 0.0)
                stopSelf()
                Process.killProcess(Process.myPid())
            }
        }

        return START_NOT_STICKY
    }

    private fun runCode(code: String, name: String) {
        running = true
        inputQueue.clear()

        thread(name = "cpython-runner") {
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(this))
                }

                val runnerModule = Python.getInstance().getModule("runner")
                val result = runnerModule.callAttr(
                    "run_script",
                    code,
                    name,
                    OutputCallback { stream, text ->
                        val event = if (stream == "stderr") {
                            EVENT_STDERR
                        } else {
                            EVENT_STDOUT
                        }
                        broadcast(event, text)
                    },
                    InputCallback {
                        broadcast(EVENT_INPUT_REQUEST, "")
                        inputQueue.take()
                    }
                )

                val values = result.asList()
                val succeeded = values.getOrNull(0)?.toBoolean() == true
                val elapsed = values.getOrNull(1)?.toDouble() ?: 0.0

                broadcast(
                    EVENT_FINISHED,
                    if (succeeded) "Finished" else "Failed",
                    elapsed
                )
            } catch (error: Throwable) {
                broadcast(EVENT_STDERR, error.stackTraceToString())
                broadcast(EVENT_FINISHED, "Runner crashed", 0.0)
            } finally {
                running = false
                stopSelf()
            }
        }
    }

    private fun broadcast(
        event: String,
        text: String,
        elapsed: Double = 0.0
    ) {
        val intent = Intent(ACTION_EVENT)
            .setPackage(packageName)
            .putExtra(EXTRA_EVENT, event)
            .putExtra(EXTRA_TEXT, text)
            .putExtra(EXTRA_ELAPSED, elapsed)

        sendBroadcast(intent)
    }

    companion object {
        const val ACTION_RUN = "com.xdgamer.pynativestudio.RUN"
        const val ACTION_STOP = "com.xdgamer.pynativestudio.STOP"
        const val ACTION_INPUT = "com.xdgamer.pynativestudio.INPUT"
        const val ACTION_EVENT = "com.xdgamer.pynativestudio.EVENT"

        const val EXTRA_CODE = "code"
        const val EXTRA_NAME = "name"
        const val EXTRA_INPUT = "input"
        const val EXTRA_EVENT = "event"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ELAPSED = "elapsed"

        const val EVENT_STDOUT = "stdout"
        const val EVENT_STDERR = "stderr"
        const val EVENT_INPUT_REQUEST = "input_request"
        const val EVENT_FINISHED = "finished"
    }
}
