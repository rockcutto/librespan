package eu.siacs.conversations.ui;

import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.*;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.NotificationManagerCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textfield.TextInputEditText;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.PushSettingsConfiguration;
import eu.siacs.conversations.services.PushSettingsUiCommands;
import java.util.*;

/** User-driven configuration only; preference events refresh facts without changing drafts. */
public final class PushSettingsActivity extends XmppActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private LinearLayout rows;
    private Spinner accounts;
    private TextView deliveryChoice;
    private String selectedProvider = "";
    private final List<String> providerLabels = new ArrayList<>();
    private TextInputEditText gateway, vapid;
    private TextView status, diagnostics;
    private SwitchCompat enabled;
    private final List<Account> accountList = new ArrayList<>();
    private final Set<String> availableProviders = new HashSet<>();
    private final List<String> providers = new ArrayList<>();
    private Bundle drafts = new Bundle();
    private String selected;
    private SharedPreferences events;
    private boolean editing, submitting, retryPrompt;
    private TextView transportHint, waitingHint, intro;
    private String editorSection="service";
    private final List<View> deliveryEditorViews=new ArrayList<>();
    private final List<View> serviceEditorViews=new ArrayList<>();
    private MaterialButton collapse;
    private final PushSettingsUiCommands commands = new PushSettingsUiCommands();
    private MaterialButton primary;
    private TextView deliverySummary, serviceSummary, confirmation;
    private ProgressBar progress;
    private final List<View> editorViews = new ArrayList<>();
    private final List<View> diagnosticViews = new ArrayList<>();
    private String action = "";
    private boolean details;


    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTheme(findTheme());
        setContentView(R.layout.activity_push_settings);
        setSupportActionBar(findViewById(R.id.toolbar));
        configureActionBar(getSupportActionBar());
        setTitle(R.string.foss_push_title);
        rows = findViewById(R.id.push_settings_rows);
        if (state != null) {
            selected = state.getString("account");
            Bundle saved = state.getBundle("drafts"); if (saved != null) drafts = saved;
        }
        text(R.string.ps_wait);
        events = getSharedPreferences("foss_push_client_v1", MODE_PRIVATE);
    }
    @Override public void onStart() { super.onStart(); events.registerOnSharedPreferenceChangeListener(this); }
    @Override public void onResume(){super.onResume();if(accounts!=null&&account()!=null){capture();render();}}
    @Override protected void onStop() { events.unregisterOnSharedPreferenceChangeListener(this); super.onStop(); }
    @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        runOnUiThread(this::refreshFacts);
    }
    @Override protected void refreshUiReal() { refreshFacts(); }
    @Override protected void onBackendConnected() {
        capture();
        gateway=null; vapid=null; deliveryChoice=null;
        accountList.clear(); accountList.addAll(xmppConnectionService.getAccounts());
        rows.removeAllViews();
        if (accountList.isEmpty()) { text(R.string.ps_no_accounts); return; }
        section(R.string.ps_account_label);
        accounts = new Spinner(this);
        accounts.setMinimumHeight(dp(48));
        List<String> labels = new ArrayList<>();
        int position = 0;
        for (int i=0;i<accountList.size();i++) {
            Account a=accountList.get(i); labels.add(a.getJid().asBareJid().toString());
            if (a.getUuid().equals(selected)) position=i;
        }
        accounts.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels){
            @Override public View getView(int position,View recycled,android.view.ViewGroup parent){
                TextView view=(TextView)super.getView(position,recycled,parent);
                view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
                view.setMinHeight(dp(48));view.setSingleLine(false);return view;
            }
        });
        rows.addView(accounts);
        accounts.setSelection(position);
        accounts.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) {}
            public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                capture(); selected=accountList.get(pos).getUuid(); render();
            }
        });
    }
    private Account account() {
        if (xmppConnectionService == null || selected == null) return null;
        return xmppConnectionService.findAccountByUuid(selected);
    }
    private void capture() {
        if (selected == null || gateway == null) return;
        Bundle b=new Bundle(); b.putString("gateway", gateway.getText().toString());
        b.putString("vapid", vapid.getText().toString());
        b.putString("provider", selectedProvider);
        b.putBoolean("editing",editing); b.putBoolean("details",details); b.putString("editorSection",editorSection); drafts.putBundle(selected,b);
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        capture(); out.putString("account",selected); out.putBundle("drafts",drafts); super.onSaveInstanceState(out);
    }
    private TextView text(int resource) {
        TextView t=new TextView(this); t.setText(resource);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        t.setTextColor(com.google.android.material.color.MaterialColors.getColor(this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,android.graphics.Color.GRAY));
        t.setPadding(0,dp(4),0,dp(8)); rows.addView(t); return t;
    }
    private TextView section(int title){
        TextView view=text(title);
        view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        view.setPadding(0,dp(16),0,dp(4));return view;
    }
    private TextView settingsRow(int title,Runnable action){
        TextView view=new TextView(this);view.setTag(title);
        view.setMinHeight(dp(64));view.setGravity(android.view.Gravity.CENTER_VERTICAL);
        view.setPadding(0,dp(8),dp(8),dp(8));
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(0,0,R.drawable.ps_chevron,0);
        view.setCompoundDrawablePadding(dp(16));
        android.util.TypedValue value=new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground,value,true);
        view.setBackgroundResource(value.resourceId);view.setFocusable(true);view.setOnClickListener(v->action.run());
        rows.addView(view);return view;
    }
    private void rowValue(TextView view,int title,CharSequence value){
        String label=getString(title);android.text.SpannableString text=new android.text.SpannableString(label+"\n"+value);
        text.setSpan(new android.text.style.TextAppearanceSpan(this,
            com.google.android.material.R.style.TextAppearance_Material3_LabelMedium),0,label.length(),0);
        text.setSpan(new android.text.style.TextAppearanceSpan(this,
            com.google.android.material.R.style.TextAppearance_Material3_BodyLarge),label.length()+1,text.length(),0);
        view.setText(text);
    }
    private MaterialButton secondaryButton(int title,Runnable action){
        MaterialButton button=(MaterialButton)getLayoutInflater().inflate(R.layout.item_push_secondary_action,rows,false);
        button.setText(title);button.setMinHeight(dp(48));rows.addView(button);
        button.setOnClickListener(v->action.run());return button;
    }
    private void toggleEditor(String section){
        capture();editing=!(editing&&section.equals(editorSection));editorSection=section;
        Bundle draft=drafts.getBundle(selected);if(draft!=null){draft.putBoolean("editing",editing);draft.putString("editorSection",section);}
        render();
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextInputEditText field(int hint) {
        TextInputLayout box=new TextInputLayout(this); box.setHint(getString(hint));
        TextInputEditText edit=new TextInputEditText(this); edit.setSingleLine(true); edit.setSaveEnabled(false);
        edit.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.bottomMargin=dp(8);box.setLayoutParams(layout);
        if(hint==R.string.foss_push_vapid){
            edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                |android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            edit.setSingleLine(false);edit.setMinLines(2);edit.setMaxLines(4);edit.setHorizontallyScrolling(false);
        }
        box.addView(edit); rows.addView(box); return edit;
    }
    private MaterialButton button(int title, Runnable action) {
        MaterialButton b=new MaterialButton(this); b.setText(title); rows.addView(b);
        b.setOnClickListener(v->action.run());
        return b;
    }
    private boolean googleAvailable() {
        try { ApplicationInfo a=getPackageManager().getApplicationInfo("com.google.android.gms",0);
            return a.enabled; } catch (android.content.pm.PackageManager.NameNotFoundException e) { return false; }
    }
    private void render() {
        while(rows.getChildCount()>2) rows.removeViewAt(2);
        Account a=account(); if(a==null) return;
        try {
            PushSettingsConfiguration.Snapshot s=PushSettingsConfiguration.read(this,selected);
            Bundle d=drafts.getBundle(selected);
            editing=d!=null && d.getBoolean("editing"); details=d!=null && d.getBoolean("details");
            editorSection=d==null?"service":d.getString("editorSection","service");
            editorViews.clear(); diagnosticViews.clear();deliveryEditorViews.clear();serviceEditorViews.clear();
            status=text(R.string.ps_wait);
            status.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
            status.setTextColor(com.google.android.material.color.MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurface,android.graphics.Color.LTGRAY));
            status.setPadding(0,dp(16),0,dp(4));status.setCompoundDrawablePadding(dp(8));
            confirmation=text(R.string.ps_confirmed);
            intro=text(R.string.ps_intro);
            progress=new ProgressBar(this); rows.addView(progress);
            waitingHint=text(R.string.ps_waiting_confirmation);
            enabled=new SwitchCompat(this); enabled.setText(R.string.ps_switch_label); enabled.setMinHeight(dp(56));
            enabled.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge); rows.addView(enabled);
            enabled.setOnClickListener(v->{
                if(enabled.isChecked()) connect();
                else {
                    Account current=account();if(current==null)return;
                    try {xmppConnectionService.getPushManagementService().disconnect(current);refreshFacts();}
                    catch(RuntimeException failure){status.setText(R.string.ps_storage);}
                }
            });
            deliverySummary=settingsRow(R.string.ps_delivery,()->toggleEditor("delivery"));
            serviceSummary=settingsRow(R.string.ps_service_title,()->toggleEditor("service"));
            int editorStart=rows.getChildCount();
            providers.clear(); providerLabels.clear(); availableProviders.clear();
            if(googleAvailable())availableProviders.add("");
            providers.add(""); providerLabels.add(getString(R.string.ps_google_name));
            Set<String> seen=new HashSet<>();
            for(ResolveInfo info:getPackageManager().queryBroadcastReceivers(
                    new Intent("org.unifiedpush.android.distributor.REGISTER"),0)) {
                if(info.activityInfo==null || !info.activityInfo.exported || !info.activityInfo.enabled
                        || !info.activityInfo.applicationInfo.enabled) continue;
                String p=info.activityInfo.packageName;
                if(getPackageName().equals(p)||!seen.add(p)) continue;
                availableProviders.add(p); providers.add(p); providerLabels.add(info.activityInfo.applicationInfo.loadLabel(getPackageManager()).toString());
            }
            selectedProvider=d!=null?d.getString("provider",""):s.distributor;
            if(!selectedProvider.isEmpty()&&!providers.contains(selectedProvider)) {
                providers.add(selectedProvider);providerLabels.add(getString(R.string.ps_missing));
            }
            deliveryChoice=settingsRow(R.string.ps_delivery,this::showDeliveryPicker);
            rowValue(deliveryChoice,R.string.ps_delivery,labelFor(selectedProvider));
            transportHint=text(R.string.ps_google_detected);
            int serviceStart=rows.getChildCount();
            for(int i=editorStart;i<serviceStart;i++)deliveryEditorViews.add(rows.getChildAt(i));
            section(R.string.ps_service_title);
            gateway=field(R.string.ps_gateway_address); vapid=field(R.string.ps_gateway_vapid);
            gateway.setText(d!=null?d.getString("gateway",""):s.gateway);
            vapid.setText(d!=null?d.getString("vapid",""):s.vapid);
            text(R.string.ps_operator);
            for(int i=serviceStart;i<rows.getChildCount();i++)serviceEditorViews.add(rows.getChildAt(i));
            for(int i=editorStart;i<rows.getChildCount();i++)editorViews.add(rows.getChildAt(i));
            collapse=secondaryButton(R.string.ps_collapse,()->toggleEditor(editorSection));
            primary=button(R.string.ps_connect,this::performPrimary);
            TextView diagnosticRow=settingsRow(R.string.ps_diagnostics,()->{
                details=!details;
                if(details)refreshFacts();else updateVisibility();
            });
            diagnosticRow.setText(R.string.ps_diagnostics);
            diagnosticRow.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            int diagnosticStart=rows.getChildCount();
            diagnostics=text(R.string.ps_wait);
            for(int i=diagnosticStart;i<rows.getChildCount();i++)diagnosticViews.add(rows.getChildAt(i));
            android.text.TextWatcher watcher=new android.text.TextWatcher(){
                public void beforeTextChanged(CharSequence t,int start,int count,int after){}
                public void onTextChanged(CharSequence t,int start,int before,int count){updateValidity();}
                public void afterTextChanged(android.text.Editable t){}
            };
            gateway.addTextChangedListener(watcher);vapid.addTextChangedListener(watcher);
            refreshFacts();
        } catch(RuntimeException error) { text(R.string.ps_storage); }
    }
    private String g(){return gateway.getText().toString().trim();}
    private String k(){return vapid.getText().toString().trim();}
    private String provider(){return selectedProvider;}
    private String mode(){return provider().isEmpty()?"embedded":"external";}
    private void performPrimary() {
        if(submitting)return;
        if("retry".equals(action)) {
            requestRetry();
        } else if("details".equals(action)){details=true;updateVisibility();}
        else if("edit".equals(action)){capture();editing=true;
            Bundle draft=drafts.getBundle(selected);if(draft!=null)draft.putBoolean("editing",true);render();}
        else if("notifications".equals(action)) openNotificationSettings();
        else connect();
    }
    private void requestRetry() {
        Account target=account();if(target==null||retryPrompt||submitting)return;
        final String uuid=target.getUuid();retryPrompt=true;
        new MaterialAlertDialogBuilder(this).setTitle(R.string.ps_retry)
            .setMessage(R.string.ps_retry_confirmation)
            .setNegativeButton(android.R.string.cancel,null)
            .setOnDismissListener(dialog->retryPrompt=false)
            .setPositiveButton(R.string.ps_retry,(dialog,which)->{
                if(!uuid.equals(selected)||xmppConnectionService==null)return;
                Account current=account();if(current==null||!current.isEnabled())return;
                try {
                    PushSettingsConfiguration.Snapshot latest=PushSettingsConfiguration.read(this,uuid);
                    if(!latest.enabled||latest.cancelling){refreshFacts();return;}
                    submitting=true;primary.setEnabled(false);
                    PushSettingsUiCommands.RetryResult result=commands.retry(
                        ()->xmppConnectionService.getPushManagementService().retry(current),
                        android.os.SystemClock.elapsedRealtime());
                    refreshFacts();
                    if(result==PushSettingsUiCommands.RetryResult.FAILED)status.setText(R.string.ps_retry_failed);
                }catch(RuntimeException failure){status.setText(R.string.ps_retry_failed);}
                finally{submitting=false;updateValidity();}
            }).show();
    }
    private void openNotificationSettings(){
        Intent intent=new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName());
        if(Build.VERSION.SDK_INT<26)intent=new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:"+getPackageName()));
        try{startActivity(intent);}catch(android.content.ActivityNotFoundException unavailable){status.setText(R.string.ps_settings_unavailable);}
    }
    private String labelFor(final String provider) {
        final int index=providers.indexOf(provider);
        return index>=0 && index<providerLabels.size()
                ? providerLabels.get(index) : getString(R.string.ps_missing);
    }
    private void showDeliveryPicker() {
        final List<String> selectableProviders=new ArrayList<>();
        final List<String> selectableLabels=new ArrayList<>();
        for(int i=0;i<providers.size();i++) {
            if(availableProviders.contains(providers.get(i))) {
                selectableProviders.add(providers.get(i));
                selectableLabels.add(providerLabels.get(i));
            }
        }
        if(selectableProviders.isEmpty()) {
            new MaterialAlertDialogBuilder(this).setTitle(R.string.ps_delivery)
                    .setMessage(R.string.ps_no_external)
                    .setPositiveButton(android.R.string.ok,null).show();
            return;
        }
        new MaterialAlertDialogBuilder(this).setTitle(R.string.ps_delivery)
                .setSingleChoiceItems(selectableLabels.toArray(new String[0]),
                    selectableProviders.indexOf(selectedProvider),(dialog,which)->{
                        selectedProvider=selectableProviders.get(which);
                        rowValue(deliveryChoice,R.string.ps_delivery,labelFor(selectedProvider));
                        updateTransportHint();updateValidity();dialog.dismiss();
                    })
                .setNegativeButton(android.R.string.cancel,null).show();
    }
    private void updateTransportHint(){
        if(transportHint==null)return;
        String p=provider();
        if(p.isEmpty())transportHint.setText(googleAvailable()?R.string.ps_google_detected:R.string.ps_no_google);
        else if(availableProviders.contains(p))transportHint.setText(getString(R.string.ps_external_hint,
            labelFor(p)));
        else transportHint.setText(R.string.ps_missing);
    }
    private void updateValidity() {
        if(primary==null)return;
        boolean connects="connect".equals(action);
        if(connects && editing){
            try {
                PushSettingsConfiguration.Snapshot saved=PushSettingsConfiguration.read(this,selected);
                if(saved.cancelling){primary.setVisibility(View.GONE);return;}
                boolean changed=!saved.matches(g(),k(),mode(),provider());
                primary.setVisibility(!changed&&PushSettingsConfiguration.valid(saved.gateway,saved.vapid)?View.GONE:View.VISIBLE);
            }catch(RuntimeException unavailable){primary.setEnabled(false);return;}
        }
        boolean valid=PushSettingsConfiguration.valid(g(),k())&&availableProviders.contains(provider());
        if(connects&&!editing){
            try{
                PushSettingsConfiguration.Snapshot saved=PushSettingsConfiguration.read(this,selected);
                if(PushSettingsConfiguration.valid(saved.gateway,saved.vapid))valid=availableProviders.contains(saved.distributor);
            }catch(RuntimeException unavailable){valid=false;}
        }
        primary.setEnabled(!submitting && account()!=null && account().isEnabled() && (!connects||valid));
    }
    private void updateVisibility() {
        for(View view:diagnosticViews)view.setVisibility(details?View.VISIBLE:View.GONE);
    }
    private void connect() {
        Account a=account(); if(a==null||submitting)return;
        try {
            PushSettingsConfiguration.Snapshot snapshot=PushSettingsConfiguration.read(this,selected);
            boolean savedValues=!editing&&PushSettingsConfiguration.valid(snapshot.gateway,snapshot.vapid);
            final String uuid=selected,address=savedValues?snapshot.gateway:g(),key=savedValues?snapshot.vapid:k(),
                m=savedValues?snapshot.mode:mode(),p=savedValues?snapshot.distributor:provider();
            if(!a.isEnabled()||!PushSettingsConfiguration.valid(address,key)){status.setText(R.string.foss_push_invalid);return;}
            if(!availableProviders.contains(p)){status.setText(p.isEmpty()?R.string.ps_no_google:R.string.ps_missing);return;}
            if(snapshot.cancelling){status.setText(R.string.foss_push_cancelling);return;}
            if(snapshot.enabled&&snapshot.matches(address,key,m,p)){
                editing=false;drafts.remove(selected);render();return;
            }
            Runnable apply=()->{
                Account target=xmppConnectionService==null?null:xmppConnectionService.findAccountByUuid(uuid);
                if(target==null||!target.isEnabled())return;
                submitting=true;primary.setEnabled(false);
                try {
                    boolean accepted=commands.apply(new PushSettingsUiCommands.Operations(){
                        public PushSettingsConfiguration.Snapshot read(){return PushSettingsConfiguration.read(PushSettingsActivity.this,uuid);}
                        public boolean save(String g,String k,String mode,String provider){
                            return PushSettingsConfiguration.save(PushSettingsActivity.this,uuid,g,k,mode,provider);}
                        public boolean connect(String g,String k,String mode,String provider){
                            return xmppConnectionService.getPushManagementService().connect(target,mode,g,k,provider);}
                    },address,key,m,p);
                    if(uuid.equals(selected)) {
                        if(accepted){editing=false;drafts.remove(selected);render();}
                        else status.setText(R.string.foss_push_error);
                    }
                } catch(RuntimeException failure){status.setText(R.string.ps_storage);}
                finally{submitting=false;updateValidity();}
            };
            if(snapshot.enabled||snapshot.isRevoked()) new MaterialAlertDialogBuilder(this).setTitle(R.string.ps_apply_changes)
                .setMessage(R.string.ps_reconnect).setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.ps_apply_changes,(dialog,which)->apply.run()).show();
            else apply.run();
        } catch(RuntimeException failure){status.setText(R.string.ps_storage);}
    }
    private void refreshToggle() {
        if(enabled!=null&&selected!=null)enabled.setChecked(PushSettingsConfiguration.read(this,selected).enabled);
    }
    private void refreshFacts() {
        Account a=account(); if(a==null||status==null||enabled==null)return;
        try {
            PushSettingsConfiguration.Snapshot snapshot=PushSettingsConfiguration.read(this,selected);
            String state=xmppConnectionService.getPushManagementService().status(a);
            if(snapshot.isRevoked()) state="UNREGISTERED";
            int label=switch(state){
                case "connected"->R.string.ps_on;
                case "off"->R.string.foss_push_off;
                case "cancelling"->R.string.foss_push_cancelling;
                case "NETWORK"->R.string.foss_push_network;
                case "UNREGISTERED"->R.string.ps_revoked;
                case "VAPID_REQUIRED"->R.string.foss_push_vapid_required;
                case "ACTION_REQUIRED"->R.string.foss_push_action;
                case "connecting","enabling"->R.string.foss_push_connecting;
                default->R.string.foss_push_error;};
            boolean configured=PushSettingsConfiguration.valid(snapshot.gateway,snapshot.vapid);
            boolean connecting="connecting".equals(state)||"enabling".equals(state)||snapshot.cancelling;
            boolean connected="connected".equals(state);
            boolean providerAvailable=availableProviders.contains(snapshot.distributor);
            status.setText(!a.isEnabled()?R.string.ps_account_disabled:!configured?R.string.ps_setup:connected?R.string.ps_on:label);
            confirmation.setVisibility(connected?View.VISIBLE:View.GONE);
            enabled.setVisibility(connected&&!editing?View.VISIBLE:View.GONE);
            refreshToggle();enabled.setEnabled(a.isEnabled()&&!submitting);
            // The service exposes no start timestamp: do not invent elapsed progress or a timeout.
            progress.setVisibility(View.GONE);
            waitingHint.setVisibility(connecting||"UNREGISTERED".equals(state)?View.VISIBLE:View.GONE);
            waitingHint.setText(snapshot.cancelling?R.string.foss_push_cancelling:
                "UNREGISTERED".equals(state)?R.string.ps_revoked_explanation:R.string.ps_waiting_confirmation);
            intro.setVisibility(!configured?View.VISIBLE:View.GONE);
            serviceSummary.setVisibility(configured&&!(editing&&"service".equals(editorSection))?View.VISIBLE:View.GONE);
            deliverySummary.setVisibility(configured&&!(editing&&"delivery".equals(editorSection))?View.VISIBLE:View.GONE);
            rowValue(serviceSummary,R.string.ps_service_title,snapshot.gateway);
            rowValue(deliverySummary,R.string.ps_delivery,labelFor(snapshot.distributor));
            boolean showEditor=!configured||editing;
            for(View view:deliveryEditorViews)view.setVisibility(showEditor&&(!configured||"delivery".equals(editorSection))?View.VISIBLE:View.GONE);
            for(View view:serviceEditorViews)view.setVisibility(showEditor&&(!configured||"service".equals(editorSection))?View.VISIBLE:View.GONE);
            collapse.setVisibility(configured&&editing?View.VISIBLE:View.GONE);
            boolean notificationsAllowed=NotificationManagerCompat.from(this).areNotificationsEnabled();
            int blockedChannels=0;
            if(Build.VERSION.SDK_INT>=26){
                NotificationManager nm=getSystemService(NotificationManager.class);
                if(nm!=null)for(android.app.NotificationChannel c:nm.getNotificationChannels()){
                    boolean blocked=c.getImportance()==NotificationManager.IMPORTANCE_NONE;
                    if(Build.VERSION.SDK_INT>=28&&c.getGroup()!=null){
                        android.app.NotificationChannelGroup group=nm.getNotificationChannelGroup(c.getGroup());
                        blocked|=group!=null&&group.isBlocked();
                    }
                    if(blocked)blockedChannels++;
                }
            }
            boolean notificationAction=connected&&!editing&&(!notificationsAllowed||blockedChannels>0);
            primary.setVisibility(snapshot.cancelling||connected&&!editing&&!notificationAction?View.GONE:View.VISIBLE);
            if(connecting&&!snapshot.cancelling){action="retry";primary.setText(R.string.ps_retry);}
            else if(notificationAction){action="notifications";primary.setText(R.string.ps_notification_settings);}
            else if(showEditor){action="connect";primary.setText(configured?R.string.ps_apply_changes:R.string.ps_connect);}
            else if("UNREGISTERED".equals(state)&&configured&&providerAvailable){action="connect";primary.setText(R.string.ps_connect_again);}
            else if("off".equals(state)){action="connect";primary.setText(R.string.ps_enable);}
            else if(!providerAvailable||"VAPID_REQUIRED".equals(state)||"ACTION_REQUIRED".equals(state)) {
                action="edit";primary.setText(R.string.ps_edit);
            } else if("NETWORK".equals(state)&&snapshot.enabled){action="retry";primary.setText(R.string.ps_retry);}
            else {action="details";primary.setText(R.string.ps_details);}
            if(!providerAvailable&&configured)status.setText(snapshot.distributor.isEmpty()?R.string.ps_no_google:R.string.ps_missing);
            if(connected&&!editing&&!notificationsAllowed){
                status.setText(R.string.ps_android_blocked);confirmation.setText(R.string.ps_allow_notifications);
            }else if(connected&&!editing&&blockedChannels>0){
                status.setText(R.string.ps_channels_limited);confirmation.setText(R.string.ps_review_channels);
            }else confirmation.setText(R.string.ps_subscription_confirmed);
            status.setCompoundDrawablesRelativeWithIntrinsicBounds(connected&&notificationsAllowed&&blockedChannels==0?R.drawable.ps_confirmed:0,0,0,0);
            updateTransportHint();updateValidity();updateVisibility();
            if(diagnostics!=null) {
                String info=getString(R.string.ps_facts,getString(a.isEnabled()?R.string.ps_yes:R.string.ps_no),
                    getString(NotificationManagerCompat.from(this).areNotificationsEnabled()?R.string.ps_yes:R.string.ps_no));
                info+="\n"+getString(R.string.ps_diag_status,getString(label));
                info+="\n"+getString(R.string.ps_diag_delivery,labelFor(snapshot.distributor));
                info+="\n"+getString(R.string.ps_diag_gateway,
                    snapshot.gateway.isEmpty()?getString(R.string.ps_not_specified):snapshot.gateway);
                if(connected)info+="\n\n"+getString(R.string.ps_confirmed);
                if(Build.VERSION.SDK_INT>=26)info+="\n"+getString(R.string.ps_channels,blockedChannels);
                diagnostics.setText(info);
            }
        }catch(RuntimeException e){status.setText(R.string.ps_storage);}
    }
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if(item.getItemId()==android.R.id.home){finish();return true;}return super.onOptionsItemSelected(item);
    }
}
