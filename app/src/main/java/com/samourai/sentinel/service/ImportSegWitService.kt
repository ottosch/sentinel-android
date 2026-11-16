package com.samourai.sentinel.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.samourai.sentinel.R
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.util.LogRedaction
import kotlinx.coroutines.*
import com.samourai.sentinel.helpers.SafeJson
import org.koin.java.KoinJavaComponent
import timber.log.Timber


/**
 * sentinel-android
 *
 */

class ActionReceiverImportSegWitService : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val serviceIntent = Intent(context, ImportSegWitService::class.java)
        serviceIntent.action = intent?.action
        context?.startService(serviceIntent)
    }
}

class ImportSegWitService : Service() {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob())
    val apiService: ApiService by KoinJavaComponent.inject(ApiService::class.java)

    private val notificationId = 95
    var progress = 0

    override fun onCreate() {
        super.onCreate()
        startForeground(notificationId, getNotification())

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        return if (intent?.action.equals("CLOSE")) {
            this.stopSelf()
            START_NOT_STICKY
        } else {
            startForeground(notificationId, getNotification())
            startImporting(intent)
            START_STICKY
        }


    }

    private fun startImporting(intent: Intent?) {
        // #465: only an opaque token crosses the Intent; the XPUB and
        // account type come from the process-local handoff. The legacy
        // "pubKey" / "segWit" extras are deliberately not read.
        val request = ImportSegWitRequests.take(intent?.getStringExtra(EXTRA_REQUEST_TOKEN))
        if (request == null) {
            this.stopSelf()
            return
        }
        val pubKey = request.pubKey
        val segwit = request.segWit
        scope.launch {
            try {
                val deferredImportStatus = async { apiService.checkImportStatus(pubKey) }
                val importStatus = deferredImportStatus.await()
                if (!importStatus) {
                    // #129: the wrap inside this child laundered real failures
                    // into CancellationException - type and stack destroyed, and
                    // the outer ladder could only render a message string. The
                    // real type now surfaces from await() as itself; genuine
                    // cancellation passes the CE arm added below.
                    val job = async {
                        apiService.importXpub(pubKey, segwit)
                    }
                    val response = job.await()
                    if (response.isSuccessful) {
                        val json = withContext(Dispatchers.Default) {

                            SafeJson.parseObject(response.body?.string()!!)

                        }
                        if (json.getString("status") == "ok") {
                            success()
                        } else {
                            failure("Import failed")
                        }
                    } else {
                        failure(response.message)
                    }
                    job.invokeOnCompletion {
                        if (it != null) {
                            it.message?.let { it1 -> failure(it1) };
                        }
                    }
                }
            } catch (e: ApiService.ApiNotConfigured) {
                // #125: checkImportStatus throws this before a request is even
                // sent; held by the async child, rethrown by await(), so one arm
                // closes both legs - the import child's await() also used to skip
                // the child's inner Exception wall, which #129 removed
                // (de-laundered). scope is a bare SupervisorJob with no CEH. No
                // logger: this Service's notification IS the user-visible degrade,
                // and success() is now unreachable.
                failure("No API endpoint configured")
            } catch (e: CancellationException) {
                // #129: genuine scope teardown, not an import failure - rethrow,
                // do not report (ladder precedent: ImportBackUpActivity).
                throw e
            } catch (e: Exception) {
                // #145: a null message must not silently skip the report -
                // render the type when the message is absent.
                failure(e.message ?: "${e::class.simpleName} (no message)")
                // #465: printStackTrace() wrote straight to logcat, past the
                // Timber tree that masks XPUBs and onion hosts (#96).
                Timber.e(e, "SegWit XPUB import failed")
            }
        }
    }

    private fun success() {
        val notification = NotificationCompat.Builder(this, "IMPORT_CHANNEL")
                .setContentTitle("Importing")
                .setContentText("Imported successfully")
                .setSmallIcon(R.drawable.ic_sentinel)
                .addAction(closeAction())
                .build()
        val mNotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mNotificationManager.notify(notificationId, notification)
    }

    private fun failure(message: String) {
        // #465: exception text can embed the request URL, which carries the
        // XPUB; notifications are readable by listener apps.
        val notification = NotificationCompat.Builder(this, "IMPORT_CHANNEL")
                .setContentTitle("Importing")
                .setContentText("Error : ${LogRedaction.redact(message)}")
                .setSmallIcon(R.drawable.ic_sentinel)
                .addAction(closeAction())
                .build()
        val mNotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mNotificationManager.notify(notificationId, notification)
    }


    private fun closeAction(): NotificationCompat.Action? {
        val broadcastIntent = Intent(this, ActionReceiverImportSegWitService::class.java)
        broadcastIntent.action = "CLOSE"
        val actionIntent = PendingIntent.getBroadcast(this,
                0, broadcastIntent, PendingIntent.FLAG_UPDATE_CURRENT  or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action(R.drawable.ic_sentinel, "Close", actionIntent)
    }

    private fun getNotification(): Notification {
        return NotificationCompat.Builder(this, "IMPORT_CHANNEL")
                .setContentTitle("Importing SegWit XPUB")
                .setSmallIcon(R.drawable.ic_sentinel)
                .setProgress(0, 0, true)
                .setOnlyAlertOnce(true)
                .build();
    }


    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    companion object {
        /** #465: the only extra this service reads - an opaque token. */
        const val EXTRA_REQUEST_TOKEN = "com.samourai.sentinel.service.ImportSegWitService.REQUEST"

        /**
         * Starts a SegWit XPUB import. The XPUB and account type stay in
         * this process ([ImportSegWitRequests]); never put them on an Intent.
         */
        fun start(context: Context, pubKey: String, segWit: String) {
            val token = ImportSegWitRequests.put(pubKey, segWit)
            context.startService(
                Intent(context, ImportSegWitService::class.java)
                    .putExtra(EXTRA_REQUEST_TOKEN, token)
            )
        }
    }
}
