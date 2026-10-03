package eu.siacs.conversations.ui

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.BitmapDrawable
import android.os.AsyncTask
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.CalendarMonth
import com.kizitonwose.calendar.core.firstDayOfWeekFromLocale
import com.kizitonwose.calendar.view.MonthDayBinder
import com.kizitonwose.calendar.view.MonthHeaderFooterBinder
import com.kizitonwose.calendar.view.ViewContainer
import eu.siacs.conversations.R
import eu.siacs.conversations.databinding.ActivityConversationCalendarBinding
import eu.siacs.conversations.ui.util.Attachment
import eu.siacs.conversations.utils.ThemeHelper
import java.lang.ref.WeakReference
import java.time.YearMonth
import java.util.Calendar
import java.util.HashMap
import java.util.concurrent.RejectedExecutionException
import kotlin.math.roundToInt

class ConversationCalendarActivity : XmppActivity() {
    lateinit var binding: ActivityConversationCalendarBinding


    private lateinit var uuid: String

    private var initialized = false

    private var data = emptyMap<Int, Int>()
    private var files = emptyMap<Int, Attachment>()

    private var yearMonth = YearMonth.now()

    private val cache = HashMap<YearMonth, Map<Int, Int>>()

    private val handler = Handler(Looper.getMainLooper())
    private val refreshDataRunnable = Runnable {
        val cached = cache.get(yearMonth)

        if (cached != null) {
            data = cached
        } else {
            data = xmppConnectionService.getMessagesCountGroupByDay(uuid, yearMonth.year, yearMonth.monthValue)
            cache.put(yearMonth, data)
        }

        files = xmppConnectionService.fileBackend.convertToAttachments(
            xmppConnectionService.databaseBackend.getRelativeFilePathsForConversationForMonth(
                uuid, yearMonth.year, yearMonth.monthValue
            )
        )

        val total = data.values.sum()
        binding.totalPerMonth.setText(getString(R.string.total_messages_per_month, total.toString()))
        binding.calendar.notifyCalendarChanged()
    }

    private var mediaSize: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_conversation_calendar)

        mediaSize = resources.getDimensionPixelSize(R.dimen.media_size)

        setSupportActionBar(binding.toolbarLayout.findViewById(R.id.toolbar))
        configureActionBar(supportActionBar)

        binding.calendar.dayBinder = object : MonthDayBinder<DayViewContainer> {
            // Called only when a new container is needed.
            override fun create(view: View) = DayViewContainer(view)

            // Called every time we need to reuse a container.
            override fun bind(container: DayViewContainer, data: CalendarDay) {
                container.textView.text = data.date.dayOfMonth.toString()
                container.textView.alpha = if (data.date.monthValue != yearMonth.monthValue) {
                    0.5f
                } else {
                    1f
                }

                val attachment = files[data.date.dayOfMonth]

                if (data.date.monthValue != yearMonth.monthValue) {
                    container.background.visibility = View.INVISIBLE
                    container.image.visibility = View.INVISIBLE

                    attachment?.let {
                        cancelPotentialWork(it, container.image)
                    }
                } else {
                    val messagesCount = this@ConversationCalendarActivity.data[data.date.dayOfMonth] ?: 0
                    if (messagesCount > 0) {
                        container.background.visibility = View.VISIBLE
                    } else {
                        container.background.visibility = View.INVISIBLE
                    }

                    container.background.alpha = (0.3f + 0.035f * messagesCount).coerceIn(0f, 1f)

                    if (attachment != null && attachment.renderThumbnail()) {
                        container.image.visibility = View.VISIBLE
                        loadPreview(attachment, container.image)
                    } else {
                        container.image.visibility = View.INVISIBLE
                    }
                }

                container.view.setOnClickListener {
                    val timestamp = data.date.toEpochDay() * 86400 * 1000
                    val conversation = xmppConnectionService.findConversationByUuid(uuid)
                    val message = xmppConnectionService.databaseBackend.getMessages(conversation, 1, timestamp, true).firstOrNull()

                    val resultIntent = Intent()
                    resultIntent.putExtra(ConversationsActivity.EXTRA_MESSAGE_UUID, message?.uuid)
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }
            }
        }

        binding.calendar.monthHeaderBinder = object : MonthHeaderFooterBinder<MonthHeaderContainer> {
            override fun create(view: View) = MonthHeaderContainer(view)

            override fun bind(container: MonthHeaderContainer, data: CalendarMonth) {
                container.textView.text = data.yearMonth.toString()
            }
        }

        binding.calendar.monthScrollListener = {
            if (yearMonth != it.yearMonth) {
                yearMonth = it.yearMonth
                binding.totalPerMonth.setText(getString(R.string.total_messages_per_month, "—"))
                data = emptyMap()
                files = emptyMap()
                binding.calendar.notifyCalendarChanged()
                handler.removeCallbacks(refreshDataRunnable)

                if (cache.containsKey(yearMonth)) {
                    refreshDataRunnable.run()
                } else {
                    handler.postDelayed(refreshDataRunnable, 500L)
                }
            }
        }
    }

    override fun refreshUiReal() {
    }

    override fun onBackendConnected() {
        uuid = intent.getStringExtra(EXTRA_UUID) ?: ""
        if (uuid == "") {
            finish()
            return
        }

        if (!initialized) {
            setupCalendar()
            refreshDataRunnable.run()
            initialized = true
        }
    }

    private fun setupCalendar() {
        val calendar = Calendar.getInstance()
        val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, -1)
        if (timestamp >= 0) {
            calendar.timeInMillis = timestamp
        }

        val conversation = xmppConnectionService.findConversationByUuid(uuid)
        val oldest = xmppConnectionService.databaseBackend.getMessages(conversation, 1, 1, true).firstOrNull()

        val currentMonth = YearMonth.of(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
        yearMonth = currentMonth

        val startMonth = if (oldest != null) {
            calendar.timeInMillis = oldest.timeSent
            YearMonth.of(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
        } else {
            currentMonth.minusMonths(1200)
        }
        val endMonth = YearMonth.now()
        val firstDayOfWeek = firstDayOfWeekFromLocale()
        binding.calendar.setup(startMonth, endMonth, firstDayOfWeek)
        binding.calendar.scrollToMonth(currentMonth)
    }


    private fun loadPreview(attachment: Attachment, imageView: ImageView) {
        if (cancelPotentialWork(attachment, imageView)) {
            val bm: Bitmap? =
                xmppConnectionService
                    .getFileBackend()
                    .getPreviewForUri(attachment, mediaSize, true)
            if (bm != null) {
                cancelPotentialWork(attachment, imageView)
                imageView.setImageBitmap(bm)
                imageView.setBackgroundColor(Color.TRANSPARENT)
            } else {
                // TODO consider if this is still a good, general purpose loading color
                imageView.setBackgroundColor(-0xcccccd)
                imageView.setImageDrawable(null)
                val task = BitmapWorkerTask(mediaSize, imageView)
                val asyncDrawable =
                    AsyncDrawable(getResources(), null, task)
                imageView.setImageDrawable(asyncDrawable)
                try {
                    task.execute(attachment)
                } catch (ignored: RejectedExecutionException) {
                }
            }
        }
    }

    private fun cancelPotentialWork(attachment: Attachment, imageView: ImageView): Boolean {
        val bitmapWorkerTask = getBitmapWorkerTask(imageView)

        if (bitmapWorkerTask != null) {
            val oldAttachment = bitmapWorkerTask.attachment
            if (oldAttachment == null || oldAttachment != attachment) {
                bitmapWorkerTask.cancel(true)
            } else {
                return false
            }
        }
        return true
    }

    private fun getBitmapWorkerTask(imageView: ImageView?): BitmapWorkerTask? {
        if (imageView != null) {
            val drawable = imageView.drawable
            if (drawable is AsyncDrawable) {
                return drawable.bitmapWorkerTask
            }
        }
        return null
    }


    private class AsyncDrawable internal constructor(
        res: Resources?,
        bitmap: Bitmap?,
        bitmapWorkerTask: BitmapWorkerTask
    ) :
        BitmapDrawable(res, bitmap) {
        private val bitmapWorkerTaskReference =
            WeakReference(bitmapWorkerTask)

        val bitmapWorkerTask: BitmapWorkerTask?
            get() = bitmapWorkerTaskReference.get()
    }

    private class BitmapWorkerTask internal constructor(private val mediaSize: Int, imageView: ImageView) : AsyncTask<Attachment?, Void?, Bitmap?>() {
        private val imageViewReference = WeakReference(imageView)
        var attachment: Attachment? = null

        override fun doInBackground(vararg params: Attachment?): Bitmap? {
            this.attachment = params[0]
            val activity = find(imageViewReference) ?: return null
            return activity.xmppConnectionService
                .fileBackend
                .getPreviewForUri(this.attachment, mediaSize, false)
        }

        override fun onPostExecute(bitmap: Bitmap?) {
            if (bitmap != null && !isCancelled) {
                val imageView = imageViewReference.get()
                if (imageView != null) {
                    imageView.setImageBitmap(bitmap)
                    imageView.setBackgroundColor(0x00000000)
                }
            }
        }
    }

    private class DayViewContainer(view: View) : ViewContainer(view) {
        val textView = view.findViewById<TextView>(R.id.calendarDayText)
        val background = view.findViewById<View>(R.id.calendarDayBackground).apply {
            setBackgroundColor(ThemeHelper.getOverriddenPrimaryColor(view.context) ?: ContextCompat.getColor(view.context, R.color.primary_default))
        }
        val image = view.findViewById<ImageView>(R.id.calendarDayImagePreview).apply {
            setBackgroundColor(ThemeHelper.getOverriddenPrimaryColor(view.context) ?: ContextCompat.getColor(view.context, R.color.primary_default))
        }

        init {
            val margin = dpToPx(4)

            view.clipToOutline = true
            val provider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(margin, margin, view.width - margin, view.height - margin)
                }
            }
            view.outlineProvider = provider
        }

        private fun dpToPx(dp: Int): Int {
            return (dp * Resources.getSystem().getDisplayMetrics().density).roundToInt();
        }
    }

    private class MonthHeaderContainer(view: View) : ViewContainer(view) {
        val textView = view.findViewById<TextView>(R.id.calendarMonthHeader)
    }

    companion object {
        private const val EXTRA_UUID = "uuid"
        private const val EXTRA_TIMESTAMP = "timestamp"

        fun createIntent(
            context: Context,
            uuid: String,
            timestamp: Long?,
        ): Intent {
            return Intent(context, ConversationCalendarActivity::class.java)
                .apply {
                    putExtra(EXTRA_UUID, uuid)
                    if (timestamp != null) {
                        putExtra(EXTRA_TIMESTAMP, timestamp)
                    }
                }
        }
    }
}