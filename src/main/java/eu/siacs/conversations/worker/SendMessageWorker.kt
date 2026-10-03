package eu.siacs.conversations.worker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import eu.siacs.conversations.Config
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.services.XmppConnectionService.XmppConnectionBinder
import eu.siacs.conversations.utils.Emoticons
import java.util.concurrent.TimeUnit

class SendMessageWorker(context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams), ServiceConnection {

    private val lock = Any()

    private var mXmppConnectionService: XmppConnectionService? = null
    private var mBindingInProcess = false

    override fun doWork(): Result {
        if (connectAndWait()) {
            val conversationUuid = inputData.getString("conversationUuid")
            val replyUuid = inputData.getString("replyUuid")
            val body = inputData.getString("body")

            val conversation = mXmppConnectionService?.findConversationByUuid(conversationUuid)

            if (replyUuid != null) {
                conversation?.replyTo =
                    mXmppConnectionService?.databaseBackend?.getMessageWithUuidOrRemoteId(
                        conversation,
                        replyUuid
                    )
            }

            if (conversation != null) {
                val message: Message
                val replyTo = conversation.replyTo
                if (replyTo != null) {
                    if (Emoticons.isEmoji(body.toString().replace("\\s".toRegex(), ""))) {
                        message =
                            replyTo.react(body.toString().replace("\\s".toRegex(), ""))
                    } else {
                        message = replyTo.reply()
                        message.appendBody(body)
                    }
                    message.setEncryption(conversation.nextEncryption)
                } else {
                    message = Message(conversation, body, conversation.nextEncryption)
                }

                Message.configurePrivateMessage(message)

                mXmppConnectionService?.sendMessage(message)
            }
        }

        return Result.success()
    }

    private fun connectAndWait(): Boolean {
        val intent = Intent(applicationContext, XmppConnectionService::class.java)
        intent.setAction(javaClass.simpleName)
        val context: Context = applicationContext
        synchronized(this) {
            if (mXmppConnectionService == null && !mBindingInProcess) {
                Log.d(Config.LOGTAG, "calling to bind service")
                context.bindService(intent, this, Context.BIND_AUTO_CREATE)
                this.mBindingInProcess = true
            }
        }
        try {
            waitForService()
            return true
        } catch (e: InterruptedException) {
            return false
        }
    }

    @Throws(InterruptedException::class)
    private fun waitForService() {
        if (mXmppConnectionService == null) {
            synchronized(this.lock) {
                (lock as Object).wait()
            }
        } else {
            Log.d(Config.LOGTAG, "not waiting for service because already initialized")
        }
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        synchronized(this) {
            val binder = service as XmppConnectionBinder
            mXmppConnectionService = binder.service
            mBindingInProcess = false
            synchronized(this.lock) {
                (lock as Object).notifyAll()
            }
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        synchronized(this) {
            mXmppConnectionService = null
        }
    }

    companion object {
        fun scheduleMessageSending(context: Context, message: Message, reply: Message?, delay: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val dataBuilder = Data.Builder()
                .putString("body", message.body)
                .putString("conversationUuid", message.conversationUuid)

            if (reply != null) {
                dataBuilder.putString("replyUuid", reply.uuid)
            }

            val uploadWorker = OneTimeWorkRequestBuilder<SendMessageWorker>()
                .setConstraints(constraints)
               // .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(dataBuilder.build())
                .setInitialDelay(delay - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                .build()


            if (!WorkManager.isInitialized()) {
                WorkManager.initialize(context, Configuration.Builder().build())
            }

            WorkManager.getInstance(context).enqueueUniqueWork(message.uuid, ExistingWorkPolicy.REPLACE, uploadWorker)
        }
    }
}