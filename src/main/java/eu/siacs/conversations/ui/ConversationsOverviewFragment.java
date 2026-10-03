/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.ui;

import static androidx.recyclerview.widget.ItemTouchHelper.LEFT;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.databinding.DataBindingUtil;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;


import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.base.Optional;
import com.google.common.collect.Collections2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.FragmentConversationsOverviewBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Bookmark;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.ListItem;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.adapter.ConversationAdapter;
import eu.siacs.conversations.ui.interfaces.OnConversationArchived;
import eu.siacs.conversations.ui.interfaces.OnConversationSelected;
import eu.siacs.conversations.ui.navigation.ProfileNavigation;
import eu.siacs.conversations.ui.util.PendingActionHelper;
import eu.siacs.conversations.ui.util.PendingItem;
import eu.siacs.conversations.ui.util.ScrollState;
import eu.siacs.conversations.ui.util.SoftKeyboardUtils;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.EasyOnboardingInvite;
import eu.siacs.conversations.xmpp.jingle.OngoingRtpSession;

public class ConversationsOverviewFragment extends XmppFragment {

    private static final String STATE_SCROLL_POSITION =
            ConversationsOverviewFragment.class.getName() + ".scroll_state";
    private static final long CONVERSATION_LIST_BOOTSTRAP_QUIET_MS = 1200L;

    private final List<Conversation> conversations = new ArrayList<>();
    private final List<ListItem.Tag> tags = new ArrayList<>();

	private final PendingItem<Conversation> swipedConversation = new PendingItem<>();
    private final PendingItem<ScrollState> pendingScrollState = new PendingItem<>();
    private FragmentConversationsOverviewBinding binding;
    private ConversationAdapter conversationsAdapter;
    private XmppActivity activity;
    private final PendingActionHelper pendingActionHelper = new PendingActionHelper();
    private String searchQuery = "";
    private RecyclerView.ItemAnimator conversationListItemAnimator;
    private boolean conversationListBootstrapAnimationsSuppressed;
    private final Runnable enableConversationListAnimations =
            () -> {
                if (binding == null || !conversationListBootstrapAnimationsSuppressed) {
                    return;
                }
                conversationListBootstrapAnimationsSuppressed = false;
                if (conversationListItemAnimator != null) {
                    binding.list.setItemAnimator(conversationListItemAnimator);
                }
            };

    private final ItemTouchHelper.SimpleCallback callback =
            new ItemTouchHelper.SimpleCallback(0, LEFT) {
                @Override
                public boolean onMove(
                        @NonNull RecyclerView recyclerView,
                        @NonNull RecyclerView.ViewHolder viewHolder,
                        @NonNull RecyclerView.ViewHolder target) {
                    return false;
                }

		@Override
		public float getSwipeEscapeVelocity (float defaultEscapeVelocity) {
            return 32 * defaultEscapeVelocity;
		}

		@Override
		public void onChildDraw(Canvas c, RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder,
									float dX, float dY, int actionState, boolean isCurrentlyActive) {
			super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
			if(actionState != ItemTouchHelper.ACTION_STATE_IDLE){
				Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
				paint.setColor(
                        MaterialColors.getColor(
                                viewHolder.itemView,
                                com.google.android.material.R.attr.colorSecondaryContainer));
				paint.setStyle(Paint.Style.FILL);
				c.drawRect(viewHolder.itemView.getLeft(),viewHolder.itemView.getTop()
						,viewHolder.itemView.getRight(),viewHolder.itemView.getBottom(), paint);
			}
		}

		@Override
		public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
			super.clearView(recyclerView, viewHolder);
			viewHolder.itemView.setAlpha(1f);
		}

                @Override
		public int getSwipeDirs(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
			if (conversationsAdapter.isConversationViewHolder(viewHolder) && !conversationsAdapter.isFiltering()) {
				return super.getSwipeDirs(recyclerView, viewHolder);
			} else {
				return 0;
			}
		}

		@Override
                public void onSwiped(
                        final RecyclerView.ViewHolder viewHolder, final int direction) {
			pendingActionHelper.execute();
			int position = viewHolder.getLayoutPosition();

			try {
				swipedConversation.push(conversationsAdapter.getConversation(position));
                    } catch (IndexOutOfBoundsException e) {
                        return;
                    }
                    conversationsAdapter.remove(swipedConversation.peek(), position);
                    activity.xmppConnectionService.markRead(swipedConversation.peek());

                    if (position == 0 && conversationsAdapter.getItemCount() == 0) {
                        final Conversation c = swipedConversation.pop();
                        activity.xmppConnectionService.archiveConversation(c);
                        return;
                    }
                    final boolean formerlySelected =
                            ConversationFragment.getConversation(getActivity())
                                    == swipedConversation.peek();
                    if (activity instanceof OnConversationArchived) {
                        ((OnConversationArchived) activity)
                                .onConversationArchived(swipedConversation.peek());
                    }
                    final Conversation c = swipedConversation.peek();
                    final int title;
                    if (c.getMode() == Conversational.MODE_MULTI) {
                        if (c.getMucOptions().isPrivateAndNonAnonymous()) {
                            title = R.string.title_undo_swipe_out_group_chat;
                        } else {
                            title = R.string.title_undo_swipe_out_channel;
                        }
                    } else {
                        title = R.string.title_undo_swipe_out_chat;
                    }

                    final Snackbar snackbar =
                            Snackbar.make(binding.list, title, 5000)
                                    .setAction(
                                            R.string.undo,
                                            v -> {
                                                pendingActionHelper.undo();
                                                Conversation conversation =
                                                        swipedConversation.pop();
                                                conversationsAdapter.insert(conversation, position);
                                                if (formerlySelected) {
                                                    if (activity
                                                            instanceof OnConversationSelected) {
                                                        ((OnConversationSelected) activity)
                                                                .onConversationSelected(c);
                                                    }
                                                }
                                                LinearLayoutManager layoutManager =
                                                        (LinearLayoutManager)
                                                                binding.list.getLayoutManager();
                                                if (position
                                                        > layoutManager
                                                                .findLastVisibleItemPosition()) {
                                                    binding.list.smoothScrollToPosition(position);
                                                }
                                            })
                                    .addCallback(
                                            new Snackbar.Callback() {
                                                @Override
                                                public void onDismissed(
                                                        Snackbar transientBottomBar, int event) {
                                                    switch (event) {
                                                        case DISMISS_EVENT_SWIPE:
                                                        case DISMISS_EVENT_TIMEOUT:
                                                            pendingActionHelper.execute();
                                                            break;
                                                    }
                                                }
                                            });

                    pendingActionHelper.push(
                            () -> {
                                if (snackbar.isShownOrQueued()) {
                                    snackbar.dismiss();
                                }
                                final Conversation conversation = swipedConversation.pop();
                                if (conversation != null) {
                                    if (!conversation.isRead()
                                            && conversation.getMode() == Conversation.MODE_SINGLE) {
                                        return;
                                    }
                                    activity.xmppConnectionService.archiveConversation(c);
                                }
                            });
                    snackbar.show();
                }
            };

    private ItemTouchHelper touchHelper;

    public static Conversation getSuggestion(Activity activity) {
        final Conversation exception;
        Fragment fragment = activity.getFragmentManager().findFragmentById(R.id.main_fragment);
        if (fragment instanceof ConversationsOverviewFragment) {
            exception = ((ConversationsOverviewFragment) fragment).swipedConversation.peek();
        } else {
            exception = null;
        }
        return getSuggestion(activity, exception);
    }

    public static Conversation getSuggestion(Activity activity, Conversation exception) {
        Fragment fragment = activity.getFragmentManager().findFragmentById(R.id.main_fragment);
        if (fragment instanceof ConversationsOverviewFragment) {
            List<Conversation> conversations =
                    ((ConversationsOverviewFragment) fragment).conversations;
            if (conversations.size() > 0) {
                Conversation suggestion = conversations.get(0);
                if (suggestion == exception) {
                    if (conversations.size() > 1) {
                        return conversations.get(1);
                    }
                } else {
                    return suggestion;
                }
            }
        }
        return null;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (savedInstanceState == null) {
            return;
        }
        pendingScrollState.push(savedInstanceState.getParcelable(STATE_SCROLL_POSITION));
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        if (activity instanceof XmppActivity) {
            this.activity = (XmppActivity) activity;
        } else {
            throw new IllegalStateException(
                    "Trying to attach fragment to activity that is not an XmppActivity");
        }
    }

    @Override
    public void onDestroyView() {
        Log.d(Config.LOGTAG, "ConversationsOverviewFragment.onDestroyView()");
        if (this.binding != null) {
            this.binding.list.removeCallbacks(enableConversationListAnimations);
        }
        super.onDestroyView();
        this.binding = null;
        this.conversationsAdapter = null;
        this.touchHelper = null;
        this.conversationListItemAnimator = null;
        this.conversationListBootstrapAnimationsSuppressed = false;
    }

    @Override
    public void onDestroy() {
        Log.d(Config.LOGTAG, "ConversationsOverviewFragment.onDestroy()");
        super.onDestroy();
    }

    @Override
    public void onPause() {
        Log.d(Config.LOGTAG, "ConversationsOverviewFragment.onPause()");
        pendingActionHelper.execute();
        super.onPause();
    }

    @Override
    public void onDetach() {
        super.onDetach();
        this.activity = null;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(
            final LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        this.binding =
                DataBindingUtil.inflate(
                        inflater, R.layout.fragment_conversations_overview, container, false);
        final int listBasePaddingLeft = this.binding.list.getPaddingLeft();
        final int listBasePaddingTop = this.binding.list.getPaddingTop();
        final int listBasePaddingRight = this.binding.list.getPaddingRight();
        final int listBasePaddingBottom = this.binding.list.getPaddingBottom();
        final ViewGroup.MarginLayoutParams fabBaseLayoutParams =
                (ViewGroup.MarginLayoutParams) this.binding.fab.getLayoutParams();
        final int fabBaseMarginLeft = fabBaseLayoutParams.leftMargin;
        final int fabBaseMarginRight = fabBaseLayoutParams.rightMargin;
        final int fabBaseMarginBottom = fabBaseLayoutParams.bottomMargin;

        ViewCompat.setOnApplyWindowInsetsListener(
                this.binding.getRoot(),
                (view, insets) -> {
                    applyOverviewInsets(
                            insets,
                            listBasePaddingLeft,
                            listBasePaddingTop,
                            listBasePaddingRight,
                            listBasePaddingBottom,
                            fabBaseMarginLeft,
                            fabBaseMarginRight,
                            fabBaseMarginBottom);
                    return insets;
                });

        // Edge-to-edge overview can otherwise render one frame using only the XML 16dp FAB
        // margin. Seed from the already-attached window so the button starts at its safe position.
        if (getActivity() != null) {
            final WindowInsetsCompat currentInsets =
                    ViewCompat.getRootWindowInsets(getActivity().getWindow().getDecorView());
            if (currentInsets != null) {
                applyOverviewInsets(
                        currentInsets,
                        listBasePaddingLeft,
                        listBasePaddingTop,
                        listBasePaddingRight,
                        listBasePaddingBottom,
                        fabBaseMarginLeft,
                        fabBaseMarginRight,
                        fabBaseMarginBottom);
            }
        }
        ViewCompat.requestApplyInsets(this.binding.getRoot());

        final View.OnClickListener startChat =
                (view) -> StartConversationActivity.launch(getActivity());
        this.binding.fab.setOnClickListener(startChat);
        this.binding.emptyStateAction.setOnClickListener(startChat);
        this.binding.searchMessagesAction.setOnClickListener(v -> {
            final Intent intent = new Intent(getActivity(), SearchActivity.class);
            intent.putExtra(SearchActivity.EXTRA_SEARCH_TERM, searchQuery);
            startActivity(intent);
        });

		this.conversationsAdapter = new ConversationAdapter(this.activity, this.conversations, this.tags);
		this.conversationsAdapter.setConversationClickListener((view, conversation) -> {
			if (activity instanceof OnConversationSelected) {
				((OnConversationSelected) activity).onConversationSelected(conversation);
			} else {
				Log.w(ConversationsOverviewFragment.class.getCanonicalName(), "Activity does not implement OnConversationSelected");
			}
		});
		this.conversationsAdapter.setConversationLongClickListener(
				(view, conversation) -> {
					showConversationContextMenu(view, conversation);
					return true;
				});
		this.binding.list.setAdapter(this.conversationsAdapter);
        this.binding.list.setLayoutManager(
                new LinearLayoutManager(getActivity(), LinearLayoutManager.VERTICAL, false));
        final RecyclerView.ItemAnimator itemAnimator = this.binding.list.getItemAnimator();
        if (itemAnimator instanceof SimpleItemAnimator) {
            // Content changes (preview/status/unread) must not cross-fade the whole row.
            // DiffUtil already identifies the exact row that changed.
            ((SimpleItemAnimator) itemAnimator).setSupportsChangeAnimations(false);
        }
        // Cold start hydrates the ordered conversation list through several closely-spaced
        // refreshes. DiffUtil still computes the exact rows, but RecyclerView must not animate
        // those bootstrap inserts/moves or the whole list appears to slide down.
        this.conversationListItemAnimator = itemAnimator;
        this.conversationListBootstrapAnimationsSuppressed = true;
        this.binding.list.setItemAnimator(null);
		this.touchHelper = new ItemTouchHelper(this.callback);
		this.touchHelper.attachToRecyclerView(this.binding.list);
		return binding.getRoot();
	}

    private void applyOverviewInsets(
            final WindowInsetsCompat insets,
            final int listBasePaddingLeft,
            final int listBasePaddingTop,
            final int listBasePaddingRight,
            final int listBasePaddingBottom,
            final int fabBaseMarginLeft,
            final int fabBaseMarginRight,
            final int fabBaseMarginBottom) {
        if (binding == null || insets == null) {
            return;
        }
        final androidx.core.graphics.Insets statusBars =
                insets.getInsets(WindowInsetsCompat.Type.statusBars());
        final androidx.core.graphics.Insets navigationBars =
                insets.getInsets(WindowInsetsCompat.Type.navigationBars());
        final androidx.core.graphics.Insets mandatoryGestures =
                insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures());
        final androidx.core.graphics.Insets cutout =
                insets.getInsets(WindowInsetsCompat.Type.displayCutout());
        final int top = Math.max(statusBars.top, cutout.top);
        final int left = Math.max(statusBars.left, cutout.left);
        final int right = Math.max(statusBars.right, cutout.right);
        final int fabSafeBottom =
                Math.max(navigationBars.bottom, mandatoryGestures.bottom);

        binding.list.setPadding(
                listBasePaddingLeft + left,
                listBasePaddingTop + top,
                listBasePaddingRight + right,
                listBasePaddingBottom + navigationBars.bottom);

        final ViewGroup.MarginLayoutParams fabLayoutParams =
                (ViewGroup.MarginLayoutParams) binding.fab.getLayoutParams();
        fabLayoutParams.leftMargin = fabBaseMarginLeft + left;
        fabLayoutParams.rightMargin = fabBaseMarginRight + right;
        fabLayoutParams.bottomMargin = fabBaseMarginBottom + fabSafeBottom;
        binding.fab.setLayoutParams(fabLayoutParams);
    }

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
        // Global entry points live in the profile; the chat list stays focused on conversations.
	}

    private void showConversationContextMenu(
            final View anchor, final Conversation conversation) {
        if (activity == null || conversation == null) {
            return;
        }
        final PopupMenu popup = new PopupMenu(activity, anchor);
        popup.getMenuInflater().inflate(R.menu.conversations, popup.getMenu());
        prepareConversationMenu(popup.getMenu(), conversation);
        popup.setForceShowIcon(true);
        popup.setOnMenuItemClickListener(
                item -> handleConversationMenuItem(conversation, item));
        popup.show();
    }

    private void prepareConversationMenu(
            final Menu menu, final Conversation conversation) {
        final MenuItem menuMucDetails = menu.findItem(R.id.action_muc_details);
        final MenuItem menuContactDetails = menu.findItem(R.id.action_contact_details);
        final MenuItem menuMute = menu.findItem(R.id.action_mute);
        final MenuItem menuUnmute = menu.findItem(R.id.action_unmute);
        final MenuItem menuOngoingCall = menu.findItem(R.id.action_ongoing_call);
        final MenuItem menuTogglePinned = menu.findItem(R.id.action_toggle_pinned);

        if (conversation.getMode() == Conversation.MODE_MULTI) {
            menuContactDetails.setVisible(false);
            menuMucDetails.setTitle(
                    conversation.getMucOptions().isPrivateAndNonAnonymous()
                            ? R.string.conversation_menu_group_info
                            : R.string.conversation_menu_channel_info);
            menuOngoingCall.setVisible(false);
        } else {
            final XmppConnectionService service =
                    activity == null ? null : activity.xmppConnectionService;
            final Optional<OngoingRtpSession> ongoingRtpSession =
                    service == null
                            ? Optional.absent()
                            : service.getJingleConnectionManager()
                                    .getOngoingRtpConnection(conversation.getContact());
            menuOngoingCall.setVisible(ongoingRtpSession.isPresent());
            menuContactDetails.setVisible(!conversation.withSelf());
            menuMucDetails.setVisible(false);
        }

        if (conversation.isMuted()) {
            menuMute.setVisible(false);
        } else {
            menuUnmute.setVisible(false);
        }

        if (conversation.getBooleanAttribute(Conversation.ATTRIBUTE_PINNED_ON_TOP, false)) {
            menuTogglePinned.setTitle(R.string.remove_from_favorites);
            menuTogglePinned.setIcon(R.drawable.ic_tabler_pinned_off_24dp);
        } else {
            menuTogglePinned.setTitle(R.string.add_to_favorites);
            menuTogglePinned.setIcon(R.drawable.ic_tabler_pin_24dp);
        }
    }

    private boolean handleConversationMenuItem(
            final Conversation conversation, final MenuItem item) {
        final ConversationFragment fragment = new ConversationFragment();
        fragment.setHasOptionsMenu(false);
        fragment.onAttach(activity);
        fragment.reInit(conversation, null);
        final boolean handled = fragment.onOptionsItemSelected(item);
        refresh();
        return handled;
    }

    @Override
    public void onBackendConnected() {
        refresh();
    }

    @Override
    public void onSaveInstanceState(Bundle bundle) {
        super.onSaveInstanceState(bundle);
        ScrollState scrollState = getScrollState();
        if (scrollState != null) {
            bundle.putParcelable(STATE_SCROLL_POSITION, scrollState);
        }
    }

    private ScrollState getScrollState() {
        if (this.binding == null) {
            return null;
        }
        LinearLayoutManager layoutManager =
                (LinearLayoutManager) this.binding.list.getLayoutManager();
        int position = layoutManager.findFirstVisibleItemPosition();
        final View view = this.binding.list.getChildAt(0);
        if (view != null) {
            return new ScrollState(position, view.getTop());
        } else {
            return new ScrollState(position, 0);
        }
    }

    @Override
	public void onPrepareOptionsMenu(Menu menu) {
		super.onPrepareOptionsMenu(menu);
	}

	@Override
	public void onStart() {
		super.onStart();
		Log.d(Config.LOGTAG, "ConversationsOverviewFragment.onStart()");
		if (activity.xmppConnectionService != null) {
			refresh();
		}

		if (activity instanceof ConversationsActivity) {
			boolean showed = ((ConversationsActivity) activity).showNavigationBar();

			if (showed) {
				this.binding.fab.setVisibility(View.GONE);
			} else {
				this.binding.fab.setVisibility(View.VISIBLE);
			}
		}
	}

    @Override
    public void onResume() {
        super.onResume();
        Log.d(Config.LOGTAG, "ConversationsOverviewFragment.onResume()");
    }

    private void selectAccountToStartEasyInvite() {
        final List<Account> accounts =
                EasyOnboardingInvite.getSupportingAccounts(activity.xmppConnectionService);
        if (accounts.isEmpty()) {
            // This can technically happen if opening the menu item races with accounts reconnecting
            // or something
            Toast.makeText(
                            getActivity(),
                            R.string.no_active_accounts_support_this,
                            Toast.LENGTH_LONG)
                    .show();
        } else if (accounts.size() == 1) {
            openEasyInviteScreen(accounts.get(0));
        } else {
            final AtomicReference<Account> selectedAccount = new AtomicReference<>(accounts.get(0));
            final MaterialAlertDialogBuilder alertDialogBuilder =
                    new MaterialAlertDialogBuilder(activity);
            alertDialogBuilder.setTitle(R.string.choose_account);
            final String[] asStrings =
                    Collections2.transform(accounts, a -> a.getJid().asBareJid().toString())
                            .toArray(new String[0]);
            alertDialogBuilder.setSingleChoiceItems(
                    asStrings, 0, (dialog, which) -> selectedAccount.set(accounts.get(which)));
            alertDialogBuilder.setNegativeButton(R.string.cancel, null);
            alertDialogBuilder.setPositiveButton(
                    R.string.ok, (dialog, which) -> openEasyInviteScreen(selectedAccount.get()));
            alertDialogBuilder.create().show();
        }
    }

    private void openEasyInviteScreen(final Account account) {
        EasyOnboardingInviteActivity.launch(account, activity);
    }

    @Override
    void refresh() {
        if (this.binding == null || this.activity == null) {
            Log.d(
                    Config.LOGTAG,
                    "ConversationsOverviewFragment.refresh() skipped updated because view binding"
                            + " or activity was null");
			return;
		}
        final boolean keepTopAnchor = shouldKeepConversationListAtTop();
		this.activity.xmppConnectionService.populateWithOrderedConversations(this.conversations);
        final Account scopedAccount = getConversationListScopeAccount();
        if (scopedAccount != null) {
            for (int i = this.conversations.size() - 1; i >= 0; --i) {
                final Conversation candidate = this.conversations.get(i);
                if (!scopedAccount.getUuid().equals(candidate.getAccount().getUuid())) {
                    this.conversations.remove(i);
                }
            }
        }
		Conversation removed = this.swipedConversation.peek();
		if (removed != null) {
			if (removed.isRead()) {
				this.conversations.remove(removed);
			} else {
				pendingActionHelper.execute();
			}
		}

		refreshTags();
        setConversationFilter(searchQuery);
        if (keepTopAnchor) {
            restoreConversationListTop();
        }
        scheduleConversationListAnimationEnable();
		ScrollState scrollState = pendingScrollState.pop();
		if (scrollState != null) {
			setScrollPosition(scrollState);
		}
	}

    private boolean shouldKeepConversationListAtTop() {
        return binding != null
                && pendingScrollState.peek() == null
                && binding.list.getScrollState() == RecyclerView.SCROLL_STATE_IDLE
                && !binding.list.canScrollVertically(-1);
    }

    private void restoreConversationListTop() {
        if (binding == null
                || conversationsAdapter == null
                || conversationsAdapter.getItemCount() == 0) {
            return;
        }
        final RecyclerView.LayoutManager layoutManager = binding.list.getLayoutManager();
        if (layoutManager instanceof LinearLayoutManager) {
            ((LinearLayoutManager) layoutManager).scrollToPositionWithOffset(0, 0);
        }
    }

    private void scheduleConversationListAnimationEnable() {
        if (binding == null || !conversationListBootstrapAnimationsSuppressed) {
            return;
        }
        binding.list.removeCallbacks(enableConversationListAnimations);
        binding.list.postDelayed(
                enableConversationListAnimations, CONVERSATION_LIST_BOOTSTRAP_QUIET_MS);
    }

    private Account getConversationListScopeAccount() {
        if (activity == null || activity.xmppConnectionService == null) {
            return null;
        }
        return ProfileNavigation.conversationListScopeAccount(
                activity, activity.xmppConnectionService);
    }

    public void setConversationFilter(final String query) {
        searchQuery = query == null ? "" : query;
        if (conversationsAdapter != null) {
            conversationsAdapter.setFilter(searchQuery);
            updateEmptyState();
        }
    }

    private void updateEmptyState() {
        if (binding == null || conversationsAdapter == null) {
            return;
        }
        final boolean empty = conversationsAdapter.getItemCount() == 0;
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.list.setVisibility(empty ? View.GONE : View.VISIBLE);
        final boolean searching = !searchQuery.isEmpty();
        binding.emptyStateTitle.setText(searching
                ? R.string.no_search_results_title
                : R.string.no_conversations_title);
        binding.emptyStateText.setText(searching
                ? R.string.no_search_results_text
                : R.string.no_conversations_text);
        binding.emptyStateAction.setVisibility(searching ? View.GONE : View.VISIBLE);
        binding.searchMessagesAction.setVisibility(searching ? View.VISIBLE : View.GONE);
    }

    private void refreshTags() {
		this.conversationsAdapter.setGroupingEnabled(activity.xmppConnectionService.getPreferences().getBoolean("conversationsGroupByTags", false));

		if (conversationsAdapter.isGroupingEnabled()) {
			List<ListItem.Tag> tags = new ArrayList<>();
			final List<Account> accounts = activity.xmppConnectionService.getAccounts();
            final Account scopedAccount = getConversationListScopeAccount();
			for (final Account account : accounts) {
                if (scopedAccount != null
                        && !scopedAccount.getUuid().equals(account.getUuid())) {
                    continue;
                }
				if (account.isEnabled()) {
					for (Contact contact : account.getRoster().getContacts()) {
						if (contact.showInContactList()) {
							tags.addAll(contact.getTags(activity));
						}
					}

					for (Bookmark bookmark : account.getBookmarks()) {
						tags.addAll(bookmark.getTags(activity));
					}
				}
			}

			Comparator<Map.Entry<ListItem.Tag, Integer>> sortTagsBy = Map.Entry.comparingByValue(Comparator.reverseOrder());
			sortTagsBy = sortTagsBy.thenComparing(entry -> entry.getKey().getName());

			this.tags.clear();
			this.tags.addAll(
					tags.stream()
							.collect(Collectors.toMap((x) -> x, (t) -> 1, (c1, c2) -> c1 + c2))
							.entrySet().stream()
							.sorted(sortTagsBy)
							.map(e -> e.getKey()).collect(Collectors.toList())
			);

			ListItem.Tag channelTag = null;
			int channelTagIndex = 0;

			for (ListItem.Tag tag : this.tags) {
				if (tag.getName().equals("Channel")) {
					channelTag = tag;
					break;
				}
				channelTagIndex++;
			}

			if (channelTag != null) {
				this.tags.remove(channelTagIndex);
				this.tags.add(0, channelTag);
			}
		}
	}

	private void setScrollPosition(ScrollState scrollPosition) {
		if (scrollPosition != null) {
			LinearLayoutManager layoutManager = (LinearLayoutManager) binding.list.getLayoutManager();
			layoutManager.scrollToPositionWithOffset(scrollPosition.position, scrollPosition.offset);
		}
	}
}
