package eu.siacs.conversations.ui.adapter;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;

import com.wefika.flowlayout.FlowLayout;

import java.util.List;
import java.util.Set;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ContactBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Bookmark;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.ListItem;
import eu.siacs.conversations.ui.SettingsActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.util.StyledAttributes;
import eu.siacs.conversations.ui.widget.AccountIndicator;
import eu.siacs.conversations.ui.widget.PresenceIndicator;
import eu.siacs.conversations.utils.IrregularUnicodeDetector;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.Jid;

public class ListItemAdapter extends ArrayAdapter<ListItem> {

	protected XmppActivity activity;
	private boolean showDynamicTags = false;
	@Nullable private Set<String> selectedJids = null;
	private OnTagClickedListener mOnTagClickedListener = null;
	private final View.OnClickListener onTagTvClick = view -> {
		if (view instanceof TextView && mOnTagClickedListener != null) {
			TextView tv = (TextView) view;
			final String tag = tv.getText().toString();
			mOnTagClickedListener.onTagClicked(tag);
		}
	};

	public ListItemAdapter(XmppActivity activity, List<ListItem> objects) {
		super(activity, 0, objects);
		this.activity = activity;
	}


	public void refreshSettings() {
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
		this.showDynamicTags = preferences.getBoolean(SettingsActivity.SHOW_DYNAMIC_TAGS, false);
	}

	@Override
	public View getView(int position, View view, ViewGroup parent) {
		LayoutInflater inflater = activity.getLayoutInflater();
		ListItem item = getItem(position);
		ViewHolder viewHolder;
		if (view == null) {
			ContactBinding binding = DataBindingUtil.inflate(inflater,R.layout.contact,parent,false);
			viewHolder = ViewHolder.get(binding);
			view = binding.getRoot();
		} else {
			viewHolder = (ViewHolder) view.getTag();
		}

		List<ListItem.Tag> tags = item.getTags(activity);
		if (selectedJids != null || tags.size() == 0 || !this.showDynamicTags) {
			viewHolder.tags.setVisibility(View.GONE);
		} else {
			viewHolder.tags.setVisibility(View.VISIBLE);
			viewHolder.tags.removeAllViewsInLayout();
			for (ListItem.Tag tag : tags) {
				TextView tv = (TextView) inflater.inflate(R.layout.list_item_tag, viewHolder.tags, false);
				tv.setText(tag.getName());
				tv.setBackgroundColor(tag.getColor());
				tv.setOnClickListener(this.onTagTvClick);
				viewHolder.tags.addView(tv);
			}
		}
		final Jid jid = item.getJid();
		if (jid != null) {
			viewHolder.jid.setVisibility(View.VISIBLE);
			viewHolder.jid.setText(IrregularUnicodeDetector.style(activity, jid));
		} else {
			viewHolder.jid.setVisibility(View.GONE);
		}
		if (selectedJids != null && jid != null) {
			viewHolder.selectionCheck.setVisibility(View.VISIBLE);
			viewHolder.selectionCheck.setChecked(
					selectedJids.contains(jid.asBareJid().toString()));
		} else {
			viewHolder.selectionCheck.setChecked(false);
			viewHolder.selectionCheck.setVisibility(View.GONE);
		}
		final int endPadding =
				selectedJids == null
						? 0
						: Math.round(52 * activity.getResources().getDisplayMetrics().density);
		viewHolder.textContainer.setPaddingRelative(
				viewHolder.textContainer.getPaddingStart(),
				viewHolder.textContainer.getPaddingTop(),
				endPadding,
				viewHolder.textContainer.getPaddingBottom());
		viewHolder.name.setText(item.getDisplayName());
		AvatarWorkerTask.loadAvatar(item, viewHolder.avatar, R.dimen.avatar);

		if (item instanceof Contact) {
			viewHolder.presenceIndicator.setStatus(((Contact) item));
		} else {
			viewHolder.presenceIndicator.setStatus(null);
		}


		Account account = null;
		if (item instanceof Contact) {
			account = ((Contact) item).getAccount();
		} else if (item instanceof Bookmark) {
			account = ((Bookmark) item).getAccount();
		}

		final int accountColor =
				selectedJids == null
								&& account != null
								&& activity.xmppConnectionService.getAccounts().size() > 1
						? UIHelper.getAccountColor(activity, account.getJid())
						: Color.TRANSPARENT;
		viewHolder.accountIndicator.setPillColor(accountColor);
		viewHolder.accountIndicatorCircle.setCircleColor(accountColor);

		return view;
	}

	public void setSelectionState(@Nullable final Set<String> selectedJids) {
		this.selectedJids = selectedJids;
		notifyDataSetChanged();
	}

	public void setOnTagClickedListener(OnTagClickedListener listener) {
		this.mOnTagClickedListener = listener;
	}


	public interface OnTagClickedListener {
		void onTagClicked(String tag);
	}

	private static class ViewHolder {
		private TextView name;
		private TextView jid;
		private ImageView avatar;
		private FlowLayout tags;
		private View textContainer;
		private com.google.android.material.checkbox.MaterialCheckBox selectionCheck;

		private PresenceIndicator presenceIndicator;

		private AccountIndicator accountIndicator;
		private AccountIndicator accountIndicatorCircle;

		private ViewHolder() {

		}

		public static ViewHolder get(ContactBinding binding) {
			ViewHolder viewHolder = new ViewHolder();
			viewHolder.name = binding.contactDisplayName;
			viewHolder.jid = binding.contactJid;
			viewHolder.avatar = binding.contactPhoto;
			viewHolder.tags = binding.tags;
			viewHolder.textContainer = binding.contactTextContainer;
			viewHolder.selectionCheck = binding.selectionCheck;
			viewHolder.presenceIndicator = binding.presenceIndicator;
			viewHolder.accountIndicator = binding.accountIndicator;
			viewHolder.accountIndicatorCircle = binding.accountIndicatorCircle;
			binding.getRoot().setTag(viewHolder);
			return viewHolder;
		}
	}

}
