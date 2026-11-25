package com.qwen.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Foreground service that owns the guest runtime. The foreground notification and a
 * partial wake lock keep the daemon and its agent sessions running with the screen off.
 */
class AgentService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var worker: Thread? = null
    @Volatile private var current: Process? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (worker?.isAlive != true) {
                stopSelf()
                return START_NOT_STICKY
            }
            stopping = true
            RuntimeState.set(RuntimeState.Phase.STOPPING)
            current?.destroy()
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) return START_NOT_STICKY
        stopping = false
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Starting…"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "qwen:agent")
            .apply { acquire() }
        worker = thread(name = "qwen-runtime") { runRuntime() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        current?.destroy()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun runRuntime() {
        try {
            val rootfs = File(filesDir, "rootfs")
            val tmp = File(filesDir, "proot-tmp").apply { mkdirs() }
            val proot = ProotCommand(File(applicationInfo.nativeLibraryDir), rootfs, tmp)
            val overrides = procOverrides()

            fun startGuest(command: List<String>, env: Map<String, String>, workDir: String): Process {
                val invocation = proot.build(command, env, workDir, overrides)
                val process = ProcessBuilder(invocation.argv).redirectErrorStream(true).apply {
                    environment().clear()
                    environment().putAll(invocation.env)
                }.start()
                current = process
                thread(isDaemon = true) {
                    process.inputStream.bufferedReader().forEachLine(RuntimeState::log)
                }
                return process
            }

            RuntimeState.set(RuntimeState.Phase.INSTALLING)
            RuntimeInstaller(this, rootfs) { startGuest(it, emptyMap(), "/root").waitFor() }.ensureInstalled()
            if (stopping) return

            RuntimeState.set(RuntimeState.Phase.STARTING)
            val settings = AppSettings(this)
            val daemon = startGuest(
                listOf(
                    "/usr/bin/node", "/opt/qwen-code/cli.js", "serve",
                    "--hostname", "127.0.0.1", "--port", PORT.toString(),
                    "--require-auth", "--no-load-admission",
                ),
                settings.modelEnv() + ("QWEN_SERVER_TOKEN" to settings.daemonToken),
                "/root/projects",
            )
            while (daemon.isAlive && !stopping && !healthy(settings.daemonToken)) Thread.sleep(1000)
            if (daemon.isAlive && !stopping) {
                RuntimeState.set(RuntimeState.Phase.RUNNING, "http://127.0.0.1:$PORT")
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification("Agent runtime is running"))
            }
            val code = daemon.waitFor()
            if (stopping) RuntimeState.set(RuntimeState.Phase.STOPPED)
            else RuntimeState.set(RuntimeState.Phase.FAILED, "Daemon exited with code $code")
        } catch (e: Exception) {
            RuntimeState.set(
                if (stopping) RuntimeState.Phase.STOPPED else RuntimeState.Phase.FAILED,
                e.message ?: e.toString(),
            )
        } finally {
            current?.destroy()
            wakeLock?.takeIf { it.isHeld }?.release()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun healthy(token: String): Boolean = try {
        val connection = URL("http://127.0.0.1:$PORT/health").openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.connectTimeout = 1000
        connection.readTimeout = 2000
        try { connection.responseCode == 200 } finally { connection.disconnect() }
    } catch (e: Exception) {
        false
    }

    /** Android's SELinux policy hides some /proc files from apps; Node and tools expect them. */
    private fun procOverrides(): Map<String, File> {
        val dir = File(filesDir, "proc-fake").apply { mkdirs() }
        val cpus = Runtime.getRuntime().availableProcessors()
        val stat = buildString {
            append("cpu  1000 0 1000 100000 0 0 0 0 0 0\n")
            repeat(cpus) { append("cpu$it 100 0 100 10000 0 0 0 0 0 0\n") }
            append("intr 0\nctxt 0\nbtime 0\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n")
        }
        val fakes = mapOf(
            "/proc/loadavg" to "0.10 0.10 0.10 1/100 100\n",
            "/proc/stat" to stat,
            "/proc/uptime" to "1000.00 1000.00\n",
            "/proc/version" to "Linux version 5.10.0 (qwen-mobile) #1 SMP PREEMPT\n",
        )
        return fakes.filterKeys { !readable(it) }.mapValues { (path, content) ->
            File(dir, path.removePrefix("/proc/")).apply { writeText(content) }
        }
    }

    private fun readable(path: String) = try {
        FileInputStream(path).use { it.read() }
        true
    } catch (e: Exception) {
        false
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Agent runtime", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AgentService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Qwen Code")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val PORT = 4170
        private const val ACTION_STOP = "com.qwen.mobile.STOP"
        private const val CHANNEL_ID = "agent"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java))

        fun stop(context: Context) {
            context.startService(Intent(context, AgentService::class.java).setAction(ACTION_STOP))
        }
    }
}
