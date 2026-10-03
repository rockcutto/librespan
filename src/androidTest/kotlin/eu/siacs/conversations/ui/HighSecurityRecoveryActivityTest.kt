package eu.siacs.conversations.ui

import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.textfield.TextInputEditText
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HighSecurityRecoveryActivityTest {
    @Test
    fun recoveryPhraseScreenStartsWithAnEditableField() {
        ActivityScenario.launch(HighSecurityRecoveryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                assertTrue(containsPhraseField(content))
            }
        }
    }

    private fun containsPhraseField(view: View): Boolean {
        if (view is TextInputEditText) return true
        if (view !is ViewGroup) return false
        return (0 until view.childCount).any { containsPhraseField(view.getChildAt(it)) }
    }
}
