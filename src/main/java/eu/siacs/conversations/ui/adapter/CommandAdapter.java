package eu.siacs.conversations.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.CommandRowBinding;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.xml.Element;

public class CommandAdapter extends ArrayAdapter<Element> {
	public CommandAdapter(XmppActivity activity) {
		super(activity, 0);
	}

	@Override
	public View getView(int position, View view, @NonNull ViewGroup parent) {
		CommandRowBinding binding = DataBindingUtil.inflate(LayoutInflater.from(parent.getContext()), R.layout.command_row, parent, false);
		binding.command.setText(getItem(position).getAttribute("name"));
		return binding.getRoot();
	}
}
