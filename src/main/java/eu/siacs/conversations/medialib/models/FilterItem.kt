package eu.siacs.conversations.medialib.models

import android.graphics.Bitmap
import com.zomato.photofilters.imageprocessors.Filter
import eu.siacs.conversations.medialib.activities.NamedFilter

data class FilterItem(var bitmap: Bitmap, val filter: NamedFilter)
