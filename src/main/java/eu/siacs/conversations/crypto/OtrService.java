package eu.siacs.conversations.crypto;

import android.util.Log;

import net.java.otr4j.OtrEngineHost;
import net.java.otr4j.OtrException;
import net.java.otr4j.OtrPolicy;
import net.java.otr4j.OtrPolicyImpl;
import net.java.otr4j.crypto.OtrCryptoEngineImpl;
import net.java.otr4j.crypto.OtrCryptoException;
import net.java.otr4j.session.FragmenterInstructions;
import net.java.otr4j.session.InstanceTag;
import net.java.otr4j.session.SessionID;

import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.DSAPrivateKeySpec;
import java.security.spec.DSAPublicKeySpec;
import java.security.spec.InvalidKeySpecException;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.generator.MessageGenerator;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.storage.secure.ScopedAccountSecretVaultV1;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.chatstate.ChatState;
import eu.siacs.conversations.xmpp.jid.OtrJidHelper;
import im.conversations.android.xmpp.model.stanza.Message;

public class OtrService extends OtrCryptoEngineImpl implements OtrEngineHost {

    private Account account;
    private OtrPolicy otrPolicy;
    private KeyPair keyPair;
    private XmppConnectionService mXmppConnectionService;

    public OtrService(XmppConnectionService service, Account account) {
        this.account = account;
        this.otrPolicy = new OtrPolicyImpl();
        this.otrPolicy.setAllowV1(false);
        this.otrPolicy.setAllowV2(true);
        this.otrPolicy.setAllowV3(true);
        this.mXmppConnectionService = service;
        this.keyPair = loadProtectedOrLegacyKey(account.getKeys());
    }

    private KeyPair loadKey(final JSONObject keys) {
        if (keys == null) {
            return null;
        }
        synchronized (keys) {
            try {
                BigInteger x = new BigInteger(keys.getString("otr_x"), 16);
                BigInteger y = new BigInteger(keys.getString("otr_y"), 16);
                BigInteger p = new BigInteger(keys.getString("otr_p"), 16);
                BigInteger q = new BigInteger(keys.getString("otr_q"), 16);
                BigInteger g = new BigInteger(keys.getString("otr_g"), 16);
                KeyFactory keyFactory = KeyFactory.getInstance("DSA");
                DSAPublicKeySpec pubKeySpec = new DSAPublicKeySpec(y, p, q, g);
                DSAPrivateKeySpec privateKeySpec = new DSAPrivateKeySpec(x, p, q, g);
                PublicKey publicKey = keyFactory.generatePublic(pubKeySpec);
                PrivateKey privateKey = keyFactory.generatePrivate(privateKeySpec);
                return new KeyPair(publicKey, privateKey);
            } catch (JSONException e) {
                return null;
            } catch (NoSuchAlgorithmException e) {
                return null;
            } catch (InvalidKeySpecException e) {
                return null;
            }
        }
    }

    private KeyPair loadProtectedOrLegacyKey(final JSONObject keys) {
        final String protectedValue =
                ScopedAccountSecretVaultV1.readString(
                        mXmppConnectionService.getApplicationContext(),
                        account.getUuid(),
                        "OTR_KEYPAIR",
                        "account");
        if (protectedValue != null) {
            try {
                return loadKey(new JSONObject(protectedValue));
            } catch (final JSONException ignored) {
                return null;
            }
        }

        final KeyPair legacy = loadKey(keys);
        if (legacy == null) {
            return null;
        }
        if (!ScopedAccountSecretVaultV1.isAvailable(
                mXmppConnectionService.getApplicationContext(), account.getUuid())) {
            return null;
        }
        if (storeProtectedKey(legacy)) {
            account.removeKey("otr_x");
            account.removeKey("otr_y");
            account.removeKey("otr_p");
            account.removeKey("otr_q");
            account.removeKey("otr_g");
            mXmppConnectionService.databaseBackend.updateAccount(account);
        }
        return legacy;
    }

    private boolean storeProtectedKey(final KeyPair pair) {
        try {
            final KeyFactory keyFactory = KeyFactory.getInstance("DSA");
            final DSAPrivateKeySpec privateKeySpec =
                    keyFactory.getKeySpec(pair.getPrivate(), DSAPrivateKeySpec.class);
            final DSAPublicKeySpec publicKeySpec =
                    keyFactory.getKeySpec(pair.getPublic(), DSAPublicKeySpec.class);
            final JSONObject encoded = new JSONObject();
            encoded.put("otr_x", privateKeySpec.getX().toString(16));
            encoded.put("otr_g", privateKeySpec.getG().toString(16));
            encoded.put("otr_p", privateKeySpec.getP().toString(16));
            encoded.put("otr_q", privateKeySpec.getQ().toString(16));
            encoded.put("otr_y", publicKeySpec.getY().toString(16));
            return ScopedAccountSecretVaultV1.storeString(
                    mXmppConnectionService.getApplicationContext(),
                    account.getUuid(),
                    "OTR_KEYPAIR",
                    "account",
                    encoded.toString());
        } catch (final NoSuchAlgorithmException
                | InvalidKeySpecException
                | JSONException e) {
            return false;
        }
    }

    private void saveKey() {
        if (!storeProtectedKey(keyPair)) {
            throw new IllegalStateException("unable to persist protected OTR key pair");
        }
    }

    @Override
    public void askForSecret(SessionID id, InstanceTag instanceTag, String question) {
        try {
            final Jid jid = OtrJidHelper.fromSessionID(id);
            Conversation conversation = this.mXmppConnectionService.find(this.account, jid, jid);
            if (conversation != null) {
                conversation.smp().hint = question;
                conversation.smp().status = Conversation.Smp.STATUS_CONTACT_REQUESTED;
                mXmppConnectionService.updateConversationUi();
            }
        } catch (IllegalArgumentException e) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": smp in invalid session " + id.toString());
        }
    }

    @Override
    public void finishedSessionMessage(SessionID arg0, String arg1)
            throws OtrException {

    }

    @Override
    public String getFallbackMessage(SessionID arg0) {
        return MessageGenerator.OTR_FALLBACK_MESSAGE;
    }

    @Override
    public byte[] getLocalFingerprintRaw(SessionID arg0) {
        try {
            return getFingerprintRaw(getPublicKey());
        } catch (OtrCryptoException e) {
            return null;
        }
    }

    public PublicKey getPublicKey() {
        if (this.keyPair == null
                && ScopedAccountSecretVaultV1.isAvailable(
                        mXmppConnectionService.getApplicationContext(), account.getUuid())) {
            this.keyPair = loadProtectedOrLegacyKey(account.getKeys());
        }
        return this.keyPair == null ? null : this.keyPair.getPublic();
    }

    public void clearRuntimeSecrets() {
        this.keyPair = null;
    }


    @Override
    public KeyPair getLocalKeyPair(SessionID arg0) throws OtrException {
        if (this.keyPair == null) {
            if (!ScopedAccountSecretVaultV1.isAvailable(
                    mXmppConnectionService.getApplicationContext(), account.getUuid())) {
                throw new OtrException(new Exception("protected OTR key vault is locked"));
            }
            this.keyPair = loadProtectedOrLegacyKey(account.getKeys());
        }
        if (this.keyPair == null) {
            KeyPairGenerator kg;
            try {
                kg = KeyPairGenerator.getInstance("DSA");
                this.keyPair = kg.genKeyPair();
                this.saveKey();
                mXmppConnectionService.databaseBackend.updateAccount(account);
            } catch (NoSuchAlgorithmException e) {
                Log.d(Config.LOGTAG,
                        "error generating key pair " + e.getMessage());
            }
        }
        return this.keyPair;
    }

    @Override
    public String getReplyForUnreadableMessage(SessionID arg0) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public OtrPolicy getSessionPolicy(SessionID arg0) {
        return otrPolicy;
    }

    @Override
    public void injectMessage(SessionID session, String body)
            throws OtrException {
        Message packet = new Message();
        packet.setFrom(account.getJid());
        if (session.getUserID().isEmpty()) {
            packet.setAttribute("to", session.getAccountID());
        } else {
            packet.setAttribute("to", session.getAccountID() + "/" + session.getUserID());
        }
        packet.setBody(body);
        MessageGenerator.addMessageHints(packet);
        try {
            Jid jid = OtrJidHelper.fromSessionID(session);
            Conversation conversation = mXmppConnectionService.find(account, jid, jid);
            if (conversation != null && conversation.setOutgoingChatState(Config.DEFAULT_CHAT_STATE)) {
                if (mXmppConnectionService.sendChatStates()) {
                    packet.addChild(ChatState.toElement(conversation.getOutgoingChatState()));
                }
            }
        } catch (final IllegalArgumentException ignored) {

        }

        packet.setType(Message.Type.CHAT);
        packet.addChild("encryption", "urn:xmpp:eme:0").setAttribute("namespace", "urn:xmpp:otr:0");
        account.getXmppConnection().sendMessagePacket(packet);
    }

    @Override
    public void messageFromAnotherInstanceReceived(SessionID session) {
        sendOtrErrorMessage(session, "Message from another OTR-instance received");
    }

    @Override
    public void multipleInstancesDetected(SessionID arg0) {
        // TODO Auto-generated method stub

    }

    @Override
    public void requireEncryptedMessage(SessionID arg0, String arg1)
            throws OtrException {
        // TODO Auto-generated method stub

    }

    @Override
    public void showError(SessionID arg0, String arg1) throws OtrException {
        Log.d(Config.LOGTAG, "show error");
    }

    @Override
    public void smpAborted(SessionID id) throws OtrException {
        setSmpStatus(id, Conversation.Smp.STATUS_NONE);
    }

    private void setSmpStatus(SessionID id, int status) {
        try {
            final Jid jid = OtrJidHelper.fromSessionID(id);
            Conversation conversation = this.mXmppConnectionService.find(this.account, jid, jid);
            if (conversation != null) {
                conversation.smp().status = status;
                mXmppConnectionService.updateConversationUi();
            }
        } catch (final IllegalArgumentException ignored) {

        }
    }

    @Override
    public void smpError(SessionID id, int arg1, boolean arg2)
            throws OtrException {
        setSmpStatus(id, Conversation.Smp.STATUS_NONE);
    }

    @Override
    public void unencryptedMessageReceived(SessionID arg0, String arg1)
            throws OtrException {
        throw new OtrException(new Exception("unencrypted message received"));
    }

    @Override
    public void unreadableMessageReceived(SessionID session) throws OtrException {
        Log.d(Config.LOGTAG, "unreadable message received");
        sendOtrErrorMessage(session, "You sent me an unreadable OTR-encrypted message");
    }

    public void sendOtrErrorMessage(SessionID session, String errorText) {
        try {
            Jid jid = OtrJidHelper.fromSessionID(session);
            Conversation conversation = mXmppConnectionService.find(account, jid, jid);
            String id = conversation == null ? null : conversation.getLastReceivedOtrMessageId();
            if (id != null) {
                Message packet = mXmppConnectionService.getMessageGenerator()
                        .generateOtrError(jid, id, errorText);
                packet.setFrom(account.getJid());
                mXmppConnectionService.sendMessagePacket(account, packet);
                Log.d(Config.LOGTAG, packet.toString());
                Log.d(Config.LOGTAG, account.getJid().asBareJid().toString()
                        + ": unreadable OTR message in " + conversation.getName());
            }
        } catch (IllegalArgumentException e) {
            return;
        }
    }

    @Override
    public void unverify(SessionID id, String arg1) {
        setSmpStatus(id, Conversation.Smp.STATUS_FAILED);
    }

    @Override
    public void verify(SessionID id, String fingerprint, boolean approved) {
        Log.d(Config.LOGTAG, "OtrService.verify(" + id.toString() + "," + fingerprint + "," + String.valueOf(approved) + ")");
        try {
            final Jid jid = OtrJidHelper.fromSessionID(id);
            Conversation conversation = this.mXmppConnectionService.find(this.account, jid, jid);
            if (conversation != null) {
                if (approved) {
                    conversation.getContact().addOtrFingerprint(fingerprint);
                }
                conversation.smp().hint = null;
                conversation.smp().status = Conversation.Smp.STATUS_VERIFIED;
                mXmppConnectionService.updateConversationUi();
                mXmppConnectionService.syncRosterToDisk(conversation.getAccount());
            }
        } catch (final IllegalArgumentException ignored) {
        }
    }

    @Override
    public FragmenterInstructions getFragmenterInstructions(SessionID sessionID) {
        return null;
    }

}