package eu.siacs.conversations.xmpp;

import im.conversations.android.xmpp.model.stanza.Iq;

public interface OnIqPacketReceived {
	void onIqPacketReceived(Iq packet);
}
