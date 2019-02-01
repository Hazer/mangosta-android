package inaka.com.mangosta.activities;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.Toolbar;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.XMPPError;
import org.jivesoftware.smackx.blocking.element.BlockedErrorExtension;
import org.jivesoftware.smackx.chatstates.ChatState;
import org.jivesoftware.smackx.chatstates.packet.ChatStateExtension;
import org.jivesoftware.smackx.muc.Affiliate;
import org.jivesoftware.smackx.muclight.MUCLightAffiliation;
import org.jivesoftware.smackx.muclight.MultiUserChatLight;
import org.jivesoftware.smackx.muclight.MultiUserChatLightManager;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.stringprep.XmppStringprepException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import butterknife.BindView;
import butterknife.ButterKnife;
import inaka.com.mangosta.R;
import inaka.com.mangosta.adapters.ChatMessagesAdapter;
import inaka.com.mangosta.adapters.StickersAdapter;
import inaka.com.mangosta.chat.RoomManager;
import inaka.com.mangosta.chat.RoomManagerListener;
import inaka.com.mangosta.chat.RoomsListManager;
import inaka.com.mangosta.network.MongooseService;
import inaka.com.mangosta.models.Chat;
import inaka.com.mangosta.models.ChatMessage;
import inaka.com.mangosta.models.Event;
import inaka.com.mangosta.models.MongooseMUCLight;
import inaka.com.mangosta.models.User;
import inaka.com.mangosta.network.MongooseAPI;
import inaka.com.mangosta.notifications.MessageNotifications;
import inaka.com.mangosta.realm.RealmManager;
import inaka.com.mangosta.utils.Preferences;
import inaka.com.mangosta.xmpp.RosterManager;
import inaka.com.mangosta.xmpp.XMPPSession;
import inaka.com.mangosta.xmpp.XMPPUtils;
import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.realm.Realm;
import io.realm.RealmChangeListener;
import io.realm.RealmResults;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ChatActivity extends BaseActivity {

    private final static String TAG = ChatActivity.class.getSimpleName();

    @BindView(R.id.toolbar)
    Toolbar toolbar;

    @BindView(R.id.chatMessagesRecyclerView)
    RecyclerView chatMessagesRecyclerView;

    @BindView(R.id.stickersRecyclerView)
    RecyclerView stickersRecyclerView;

    @BindView(R.id.chatSendMessageButton)
    ImageButton chatSendMessageButton;

    @BindView(R.id.stickersMenuImageButton)
    ImageButton stickersMenuImageButton;

    @BindView(R.id.chatSendMessageEditText)
    EditText chatSendMessageEditText;

    @BindView(R.id.loadMessagesSwipeRefreshLayout)
    SwipeRefreshLayout loadMessagesSwipeRefreshLayout;

    @BindView(R.id.chatTypingTextView)
    TextView chatTypingTextView;

    @BindView(R.id.scrollDownImageButton)
    ImageButton scrollDownImageButton;

    private RoomManager mRoomManager;
    private String mChatJID;

    public static String CHAT_JID_PARAMETER = "chatJid";
    public static String CHAT_NAME_PARAMETER = "chatName";
    public static String IS_NEW_CHAT_PARAMETER = "isNew";

    Chat mChat;

    private RealmResults<ChatMessage> mMessages;
    private ChatMessagesAdapter mMessagesAdapter;
    LinearLayoutManager mLayoutManagerMessages;

    private String[] mStickersNameList = {
            "base",
            "pliiz",
            "bigsmile",
            "pleure",
            "snif"
    };
    private StickersAdapter mStickersAdapter;
    LinearLayoutManager mLayoutManagerStickers;

    private Disposable mMessageSubscription;
    private Disposable mMongooseMessageSubscription;
    private Disposable mMongooseMUCLightMessageSubscription;
    private Disposable mConnectionSubscription;
    private Disposable mArchiveQuerySubscription;
    private Disposable mErrorArchiveQuerySubscription;

    boolean mLeaving = false;
    boolean mIsOwner = false;

    Timer mPauseComposeTimer = new Timer();
    private int mMessagesCount;
    private Menu mMenu;

    final private int VISIBLE_BEFORE_LOAD = 10;
    final private int ITEMS_PER_PAGE = 15;
    final private int PAGES_TO_LOAD = 3;

    SwipeRefreshLayout.OnRefreshListener mSwipeRefreshListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        unbinder = ButterKnife.bind(this);

        setSupportActionBar(toolbar);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);

        mChatJID = getIntent().getStringExtra(CHAT_JID_PARAMETER);
        String chatName = getIntent().getStringExtra(CHAT_NAME_PARAMETER);
        boolean isNewChat = getIntent().getBooleanExtra(IS_NEW_CHAT_PARAMETER, false);

        mChat = getChatFromRealm();

        if (isNewChat) {
            RoomsListManager.getInstance().manageNewChat(mChat, getRealm(), chatName, mChatJID);
            mChat = getChatFromRealm();
        }

        if (!mChat.isShow()) {
            RoomsListManager.getInstance().setShowChat(getRealm(), mChat);
        }

        getSupportActionBar().setTitle(chatName);
        if (mChat.getSubject() != null) {
            getSupportActionBar().setSubtitle(mChat.getSubject());
        }

        if (mChat.getType() == Chat.TYPE_MUC_LIGHT) {
            manageRoomNameAndSubject();
        } else {
            setOneToOneChatConnectionStatus();
        }

        mRoomManager = RoomManager.getInstance(new RoomManagerChatListener(ChatActivity.this));

        mLayoutManagerMessages = new LinearLayoutManager(this);
        mLayoutManagerMessages.setStackFromEnd(true);

        chatMessagesRecyclerView.setHasFixedSize(true);
        chatMessagesRecyclerView.setLayoutManager(mLayoutManagerMessages);

        if (!RealmManager.isTesting()) {
            mMessages = RealmManager.getInstance().getMessagesForChat(getRealm(), mChatJID);
            mMessages.addChangeListener(mRealmChangeListener);
        }

        List<ChatMessage> messages = ((mMessages == null) ? new ArrayList<ChatMessage>() : mMessages);
        mMessagesAdapter = new ChatMessagesAdapter(this, messages, mChat);

        chatMessagesRecyclerView.setAdapter(mMessagesAdapter);

        chatSendMessageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View button) {
                sendTextMessage();
            }
        });

        loadMessagesSwipeRefreshLayout.setColorSchemeResources(
                android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light);

        mSwipeRefreshListener = new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                loadArchivedMessages();
            }
        };
        loadMessagesSwipeRefreshLayout.setOnRefreshListener(mSwipeRefreshListener);

        chatSendMessageEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int start, int before, int count) {
                if (charSequence.length() > 0) { // compose message
                    mPauseComposeTimer.cancel();
                    mRoomManager.updateTypingStatus(ChatState.composing, mChatJID, mChat.getType());
                    schedulePauseTimer();
                } else { // delete or send message
                    mPauseComposeTimer.cancel();
                    mRoomManager.updateTypingStatus(ChatState.inactive, mChatJID, mChat.getType());
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {

            }
        });

        stickersMenuImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (stickersRecyclerView.getVisibility() == View.VISIBLE) {
                    stickersRecyclerView.setVisibility(View.GONE);
                } else {
                    stickersRecyclerView.setVisibility(View.VISIBLE);
                }
            }
        });

        mLayoutManagerStickers = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
        stickersRecyclerView.setHasFixedSize(true);
        stickersRecyclerView.setLayoutManager(mLayoutManagerStickers);

        mStickersAdapter = new StickersAdapter(this, Arrays.asList(mStickersNameList));

        stickersRecyclerView.setAdapter(mStickersAdapter);

        scrollDownImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                scrollToEnd();
            }
        });

        chatMessagesRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                manageScrollButtonVisibility();
                loadMoreMessages(recyclerView, dy);
            }
        });

        chatMessagesRecyclerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                cancelMessageNotificationsForChat();
                mMessagesAdapter.notifyDataSetChanged();
                return false;
            }
        });

    }

    @Override
    protected void onResume() {
        super.onResume();

        mMessageSubscription = XMPPSession.getInstance().subscribeRoomToMessages(mChatJID, message -> {
            if (message.hasExtension(ChatStateExtension.NAMESPACE)) {
                ChatStateExtension chatStateExtension = (ChatStateExtension) message.getExtension(ChatStateExtension.NAMESPACE);
                ChatState chatState = chatStateExtension.getChatState();

                String myUser = XMPPUtils.fromJIDToUserName(XMPPSession.getInstance().getUser().toString());
                String userSender = "";
                String messageType = message.getType().name();

                String[] jidList = message.getFrom().toString().split("/");

                switch (mChat.getType()) {

                    case Chat.TYPE_1_T0_1:
                        userSender = XMPPUtils.fromJIDToUserName(jidList[0]);
                        break;

                    case Chat.TYPE_MUC_LIGHT:
                        if (jidList.length > 1) {
                            userSender = XMPPUtils.fromJIDToUserName(jidList[1]);
                        }
                        break;
                }

                showTypingStatus(chatState, myUser, userSender, messageType);
            } else {
                String subject = message.getSubject();
                if (subject != null) {
                    setTitle(subject);
                }
                refreshMessagesAndScrollToEnd();

                showErrorToast(message);
            }
        });

        mMongooseMessageSubscription = XMPPSession.getInstance().subscribeRoomToMongooseMessages(mChatJID, message -> {
            refreshMessagesAndScrollToEnd();
        });

        mMongooseMUCLightMessageSubscription = XMPPSession.getInstance().subscribeRoomToMUCLightMongooseMessages(mChatJID, message -> {
            refreshMessagesAndScrollToEnd();
        });

        mConnectionSubscription = XMPPSession.getInstance().subscribeToConnection(chatConnection -> {
            Log.d(TAG, "ChatConnection: " + chatConnection.getStatus());
            switch (chatConnection.getStatus()) {
                case Connected:
                case Authenticated:
                    break;
                case Connecting:
                case Disconnected:
                    break;
            }
        });

        getMessageBeingComposed();
    }

    @Override
    protected void onPause() {
        super.onPause();

        saveMessageBeingComposed();

        cancelMessageNotificationsForChat();
        mMessagesAdapter.notifyDataSetChanged();

        mChat = getChatFromRealm();
        if (mChat != null) {
            sendInactiveTypingStatus();
        }

        disconnectRoomFromServer();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_chat, menu);
        mMenu = menu;

        menu.findItem(R.id.actionChatMembers).setVisible(mChat.getType() != Chat.TYPE_1_T0_1);

        // can change room name or subject only if it is a MUC Light
        menu.findItem(R.id.actionChangeRoomName).setVisible(mChat.getType() == Chat.TYPE_MUC_LIGHT);
        menu.findItem(R.id.actionChangeSubject).setVisible(mChat.getType() == Chat.TYPE_MUC_LIGHT);

        menu.findItem(R.id.actionDestroyChat).setVisible(false);
        setDestroyButtonVisibility(menu);

        menu.findItem(R.id.actionAddToContacts).setVisible(false);
        menu.findItem(R.id.actionRemoveFromContacts).setVisible(false);
        if (mChat.getType() == Chat.TYPE_1_T0_1) {
            menu.findItem(R.id.actionLeaveChat).setTitle(getString(R.string.action_delete_chat));
            manageLeaveAndContactMenuItems();
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        cancelMessageNotificationsForChat();

        switch (id) {
            case android.R.id.home:

                mChat = getChatFromRealm();
                sendInactiveTypingStatus();

                if (mSessionDepth == 1) {
                    Intent intent = new Intent(this, MainMenuActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(intent);
                } else {
                    finish();
                }

                new Event(Event.Type.GO_BACK_FROM_CHAT).post();
                break;

            case R.id.actionChatMembers:
                mChat = getChatFromRealm();
                Intent chatMembersIntent = new Intent(ChatActivity.this, ChatMembersActivity.class);
                chatMembersIntent.putExtra(ChatMembersActivity.ROOM_JID_PARAMETER, mChatJID);
                chatMembersIntent.putExtra(ChatMembersActivity.IS_ADMIN_PARAMETER, mIsOwner);
                startActivity(chatMembersIntent);
                break;

            case R.id.actionChangeRoomName:
                changeMUCLightRoomName();
                break;

            case R.id.actionChangeSubject:
                changeMUCLightRoomSubject();
                break;

            case R.id.actionLeaveChat:
                mRoomManager.updateTypingStatus(ChatState.gone, mChatJID, mChat.getType());
                leaveChat();
                break;

            case R.id.actionDestroyChat:
                mRoomManager.updateTypingStatus(ChatState.gone, mChatJID, mChat.getType());
                destroyChat();
                break;

            case R.id.actionAddToContacts:
                addChatGuyToContacts();
                break;

            case R.id.actionRemoveFromContacts:
                removeChatGuyFromContacts();
                break;
        }

        return true;
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        saveMessageBeingComposed();
        sendInactiveTypingStatus();
        cancelMessageNotificationsForChat();
        new Event(Event.Type.GO_BACK_FROM_CHAT).post();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        saveMessageBeingComposed();
    }

    private void showErrorToast(Message message) {
        XMPPError error = message.getError();
        if (BlockedErrorExtension.isInside(message)) {
            Toast.makeText(ChatActivity.this, getString(R.string.message_to_blocked_user), Toast.LENGTH_SHORT).show();
        } else if (error != null && error.getCondition().equals(XMPPError.Condition.service_unavailable)) {
            Toast.makeText(ChatActivity.this, getString(R.string.cant_send_message), Toast.LENGTH_SHORT).show();
        }
    }

    private void showTypingStatus(ChatState chatState, String myUser, String userSender, String messageType) {
        if (!userSender.equals(myUser) && !messageType.equals("error")) {
            if (chatState.equals(ChatState.composing)) { // typing
                chatTypingTextView.setText(String.format(Locale.getDefault(), getString(R.string.typing), userSender));
                chatTypingTextView.setVisibility(View.VISIBLE);
            } else { // not typing
                chatTypingTextView.setVisibility(View.GONE);
            }
        }
    }

    private void saveMessageBeingComposed() {
        if (!Preferences.isTesting()) {
            mChat = getChatFromRealm();

            if (mChat != null && chatSendMessageEditText != null) {
                String message = chatSendMessageEditText.getText().toString();

                Realm realm = getRealm();
                realm.beginTransaction();
                mChat.setMessageBeingComposed(message);
                realm.commitTransaction();
            }
        }
    }

    private void getMessageBeingComposed() {
        if (!Preferences.isTesting()) {
            mChat = getChatFromRealm();
            String message = mChat.getMessageBeingComposed();
            if (message != null && message.length() > 0) {
                chatSendMessageEditText.setText(message);
            }
        }
    }

    private Chat getChatFromRealm() {
        return RealmManager.getInstance().getChatFromRealm(getRealm(), mChatJID);
    }

    private void cancelMessageNotificationsForChat() {
        if (!Preferences.isTesting()) {
            MessageNotifications.cancelChatNotifications(this, mChatJID);
            Realm realm = RealmManager.getInstance().getRealm();
            mChat = RealmManager.getInstance().getChatFromRealm(realm, mChatJID);
            realm.beginTransaction();
            mChat.resetUnreadMessageCount();
            realm.commitTransaction();
        }
    }

    private void loadMoreMessages(RecyclerView recyclerView, int dy) {
        int lastVisibleItem = mLayoutManagerMessages.findLastVisibleItemPosition();
        if (dy < 0) {
            int visibleItemCount = recyclerView.getChildCount();
            int totalItemCount = mLayoutManagerMessages.getItemCount();
            boolean countVisibleToLoadMore = (totalItemCount - visibleItemCount
                    - (totalItemCount - lastVisibleItem))
                    <= VISIBLE_BEFORE_LOAD;

            if (countVisibleToLoadMore && !loadMessagesSwipeRefreshLayout.isRefreshing()) {
                loadMessagesSwipeRefreshLayout.post(new Runnable() {
                    @Override
                    public void run() {
                        loadMessagesSwipeRefreshLayout.setRefreshing(true);
                        mSwipeRefreshListener.onRefresh();
                    }
                });
            }
        }
    }

    private void manageScrollButtonVisibility() {
        if (isMessagesListScrolledToBottom()) {
            scrollDownImageButton.setVisibility(View.GONE);
        } else {
            scrollDownImageButton.setVisibility(View.VISIBLE);
        }
    }

    private void setOneToOneChatConnectionStatus() {
        String userName = XMPPUtils.fromJIDToUserName(mChatJID);

        if (RosterManager.getInstance().getStatusFromContact(userName).equals(Presence.Type.available)) {
            getSupportActionBar().setSubtitle(getString(R.string.connected));
        } else {
            getSupportActionBar().setSubtitle("");
        }
    }

    private void schedulePauseTimer() {
        mPauseComposeTimer = new Timer();
        mPauseComposeTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mChat = getChatFromRealm();
                        sendInactiveTypingStatus();
                    }
                });
            }
        }, 15000);
    }

    private void manageRoomNameAndSubject() {
        MongooseService mongooseService = MongooseAPI.getInstance().getAuthenticatedService();

        if (mongooseService != null) {
            Call<MongooseMUCLight> call = mongooseService.getMUCLightDetails(mChatJID.split("@")[0]);
            call.enqueue(new Callback<MongooseMUCLight>() {
                @Override
                public void onResponse(Call<MongooseMUCLight> call, Response<MongooseMUCLight> response) {
                    MongooseMUCLight mongooseMUCLight = response.body();

                    if (mongooseMUCLight != null) {
                        Realm realm = getRealm();
                        realm.beginTransaction();

                        mChat = RealmManager.getInstance().getChatFromRealm(getRealm(), mChatJID);
                        mChat.setName(mongooseMUCLight.getName());
                        mChat.setSubject(mongooseMUCLight.getSubject());

                        realm.copyToRealmOrUpdate(mChat);
                        realm.commitTransaction();

                        if (mChat.getSubject() != null) {
                            getSupportActionBar().setSubtitle(mChat.getSubject());
                        }

                        realm.close();
                    }

                }

                @Override
                public void onFailure(Call<MongooseMUCLight> call, Throwable t) {
                    Toast.makeText(ChatActivity.this, ChatActivity.this.getString(R.string.error), Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void sendInactiveTypingStatus() {
        if (wasComposingMessage()) {
            mRoomManager.updateTypingStatus(ChatState.paused, mChatJID, mChat.getType());
        } else {
            mRoomManager.updateTypingStatus(ChatState.inactive, mChatJID, mChat.getType());
        }
    }

    private boolean wasComposingMessage() {
        return chatSendMessageEditText != null && chatSendMessageEditText.getText().length() > 0;
    }

    private void removeChatGuyFromContacts() {
        User userNotContact = new User(mChat.getJid());
        try {
            RosterManager.getInstance().removeContact(userNotContact);
            setMenuChatNotContact();
            Toast.makeText(this, String.format(Locale.getDefault(), getString(R.string.user_removed_from_contacts),
                    XMPPUtils.getDisplayName(userNotContact)), Toast.LENGTH_SHORT).show();
        } catch (SmackException.NotLoggedInException | InterruptedException |
                SmackException.NotConnectedException | XMPPException.XMPPErrorException |
                XmppStringprepException | SmackException.NoResponseException e) {
            e.printStackTrace();
        }
    }

    private void addChatGuyToContacts() {
        User userContact = new User(mChat.getJid());
        try {
            RosterManager.getInstance().addContact(userContact);
            setMenuChatWithContact();
            Toast.makeText(this, String.format(Locale.getDefault(), getString(R.string.user_added_to_contacts),
                    XMPPUtils.getDisplayName(userContact)), Toast.LENGTH_SHORT).show();
        } catch (SmackException.NotLoggedInException | InterruptedException | SmackException.NotConnectedException | XMPPException.XMPPErrorException | XmppStringprepException | SmackException.NoResponseException e) {
            e.printStackTrace();
        }
    }

    private void changeMUCLightRoomName() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        final EditText roomNameEditText = new EditText(this);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(10, 0, 10, 0);
        roomNameEditText.setLayoutParams(lp);
        roomNameEditText.setHint(getString(R.string.enter_room_name_hint));
        roomNameEditText.setText(getSupportActionBar().getTitle());

        linearLayout.addView(roomNameEditText);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(ChatActivity.this)
                .setTitle(getString(R.string.room_name))
                .setMessage(getString(R.string.enter_new_room_name))
                .setView(linearLayout)
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        final String chatName = roomNameEditText.getText().toString();
                        renameRoom(chatName);
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getResources().getColor(R.color.colorPrimary));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getResources().getColor(R.color.colorPrimary));
    }

    private void renameRoom(String chatName) {
        Completable task = Completable.fromCallable(() -> {
            if (!Preferences.isTesting()) {
                MultiUserChatLight multiUserChatLight = XMPPSession.getInstance().getMUCLightManager().getMultiUserChatLight(JidCreate.from(mChatJID).asEntityBareJidIfPossible());
                multiUserChatLight.changeRoomName(chatName);
            }
            return null;
        });

        addDisposable(task
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    Toast.makeText(ChatActivity.this,
                            getString(R.string.room_name_changed),
                            Toast.LENGTH_SHORT).show();

                    Realm realm = getRealm();
                    realm.beginTransaction();
                    mChat.setName(chatName);
                    realm.commitTransaction();

                    if (!Preferences.isTesting()) {
                        realm.close();
                    }

                    getSupportActionBar().setTitle(chatName);
                }, error -> {
                    Toast.makeText(ChatActivity.this, R.string.error, Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "renameRoom error", error);
                }));
    }

    private void changeMUCLightRoomSubject() {
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        final EditText roomSubjectEditText = new EditText(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(10, 0, 10, 0);
        roomSubjectEditText.setLayoutParams(lp);
        roomSubjectEditText.setHint(getString(R.string.enter_room_subject_hint));
        roomSubjectEditText.setText(getSupportActionBar().getSubtitle());

        linearLayout.addView(roomSubjectEditText);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(ChatActivity.this)
                .setTitle(getString(R.string.room_subject))
                .setMessage(getString(R.string.enter_new_room_subject))
                .setView(linearLayout)
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        final String subject = roomSubjectEditText.getText().toString();
                        changeRoomSubject(subject);
                        dialog.dismiss();
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getResources().getColor(R.color.colorPrimary));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getResources().getColor(R.color.colorPrimary));
    }

    private void changeRoomSubject(String subject) {
        Completable task = Completable.fromCallable(() -> {
            if (!Preferences.isTesting()) {
                MultiUserChatLight multiUserChatLight = XMPPSession.getInstance().getMUCLightManager().getMultiUserChatLight(JidCreate.from(mChatJID).asEntityBareJidIfPossible());
                multiUserChatLight.changeSubject(subject);
            }
            return null;
        });

        addDisposable(task
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    Toast.makeText(ChatActivity.this,
                            getString(R.string.room_subject_changed),
                            Toast.LENGTH_SHORT).show();

                    Realm realm = getRealm();
                    realm.beginTransaction();
                    mChat.setSubject(subject);
                    realm.commitTransaction();

                    if (!Preferences.isTesting()) {
                        realm.close();
                    }

                    getSupportActionBar().setSubtitle(subject);
                }, error -> {
                    Toast.makeText(ChatActivity.this, R.string.error, Toast.LENGTH_SHORT).show();
                }));
    }

    private void disconnectRoomFromServer() {
        if (mMessageSubscription != null) {
            mMessageSubscription.dispose();
        }

        if (mConnectionSubscription != null) {
            mConnectionSubscription.dispose();
        }

        if (mArchiveQuerySubscription != null) {
            mArchiveQuerySubscription.dispose();
        }

        if (mErrorArchiveQuerySubscription != null) {
            mErrorArchiveQuerySubscription.dispose();
        }

        if (mMongooseMessageSubscription != null) {
            mMongooseMessageSubscription.dispose();
        }

        if (mMongooseMUCLightMessageSubscription != null) {
            mMongooseMUCLightMessageSubscription.dispose();
        }

    }

    private void sendTextMessage() {
        cancelMessageNotificationsForChat();
        if (!XMPPSession.isInstanceNull()
                && (XMPPSession.getInstance().isConnectedAndAuthenticated() || Preferences.isTesting())) {
            String content = chatSendMessageEditText.getText().toString().trim().replaceAll("\n\n+", "\n\n");

            if (!TextUtils.isEmpty(content)) {
                mChat = getChatFromRealm();
                mRoomManager.sendTextMessage(mChatJID, content, mChat.getType());
                chatSendMessageEditText.setText("");
                refreshMessagesAndScrollToEnd();
            }
        }
    }

    private void leaveChat() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        int positiveButtonTitle;
        if (mChat.getType() == Chat.TYPE_1_T0_1) {
            builder.setMessage(getString(R.string.want_to_delete_chat));
            positiveButtonTitle = R.string.action_delete_chat;
        } else {
            builder.setMessage(getString(R.string.want_to_leave_chat));
            positiveButtonTitle = R.string.action_leave_chat;
        }

        builder.setPositiveButton(positiveButtonTitle, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mLeaving = true;

                Realm realm = getRealm();
                Chat chat = realm.where(Chat.class).equalTo("jid", mChatJID).findFirst();

                switch (chat.getType()) {

                    case Chat.TYPE_MUC_LIGHT:
                        realm.close();
                        disconnectRoomFromServer();
                        mRoomManager.leaveMUCLight(mChatJID);
                        break;

                    case Chat.TYPE_1_T0_1:
                        realm.close();
                        mRoomManager.leave1to1Chat(mChatJID);
                        break;
                }

                finish();
            }
        });
        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        AlertDialog dialog = builder.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getResources().getColor(R.color.colorPrimary));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getResources().getColor(R.color.colorPrimary));
    }

    private void destroyChat() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(getString(R.string.want_to_destroy_chat));

        builder.setPositiveButton(R.string.action_destroy_chat, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mLeaving = true;
                mChat = getChatFromRealm();
                if (mChat.getType() == Chat.TYPE_MUC_LIGHT) {
                    disconnectRoomFromServer();
                    mRoomManager.destroyMUCLight(mChatJID);
                    finish();
                }
            }
        });
        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        AlertDialog dialog = builder.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getResources().getColor(R.color.colorPrimary));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getResources().getColor(R.color.colorPrimary));
    }

    private void loadArchivedMessages() {
        mChat = getChatFromRealm();

        if (mChat == null || !mChat.isValid()) {

            if (loadMessagesSwipeRefreshLayout != null) {
                loadMessagesSwipeRefreshLayout.setRefreshing(false);
            }

            if (mErrorArchiveQuerySubscription != null) {
                mErrorArchiveQuerySubscription.dispose();
            }

            if (mArchiveQuerySubscription != null) {
                mArchiveQuerySubscription.dispose();
            }

            return;
        }

        mArchiveQuerySubscription = XMPPSession.getInstance().subscribeToArchiveQuery(s -> {
            if (loadMessagesSwipeRefreshLayout != null) {
                loadMessagesSwipeRefreshLayout.setRefreshing(false);
            }
        });

        mErrorArchiveQuerySubscription = XMPPSession.getInstance().subscribeToError(errorIQ -> {
            if (loadMessagesSwipeRefreshLayout != null) {
                loadMessagesSwipeRefreshLayout.setRefreshing(false);
            }
            Toast.makeText(ChatActivity.this, R.string.error, Toast.LENGTH_SHORT).show();
        });

        mRoomManager.loadArchivedMessages(mChatJID, PAGES_TO_LOAD, ITEMS_PER_PAGE);
    }

    private void refreshMessages() {
        XMPPSession.getInstance().deleteMessagesToDelete();
        mMessagesAdapter.notifyDataSetChanged();
    }

    private void scrollToEnd() {
        if (mMessagesAdapter != null) {
            if (!isMessagesListScrolledToBottom() && chatMessagesRecyclerView != null) {
                chatMessagesRecyclerView.scrollToPosition(mMessagesAdapter.getItemCount() - 1);
            }
        }
    }

    private boolean isMessagesListScrolledToBottom() {
        int lastPosition = mLayoutManagerMessages.findLastVisibleItemPosition();
        return !(lastPosition <= mMessagesAdapter.getItemCount() - 2);
    }

    private void refreshMessagesAndScrollToEnd() {
        refreshMessages();
        scrollToEnd();
    }

    RealmChangeListener<RealmResults<ChatMessage>> mRealmChangeListener = new RealmChangeListener<RealmResults<ChatMessage>>() {
        @Override
        public void onChange(RealmResults<ChatMessage> messages) {
            if (mMessagesAdapter != null) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!XMPPSession.isInstanceNull() && XMPPSession.getInstance().isConnectedAndAuthenticated()) {
                            if (mMessages.size() == 0 && !mLeaving) {
                                loadArchivedMessages();
                            }
                        }

                        if (mMessagesCount != mMessages.size()) {
                            refreshMessagesAndScrollToEnd();
                            mMessagesCount = mMessages.size();
                        } else {
                            refreshMessages();
                        }

                    }
                });
            }
        }
    };

    private class RoomManagerChatListener extends RoomManagerListener {

        public RoomManagerChatListener(Context context) {
            super(context);
        }

        @Override
        public void onMessageSent(int chatType) {
            super.onMessageSent(chatType);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    refreshMessagesAndScrollToEnd();
                }
            });
        }

        @Override
        public void onRoomMembersLoaded(final List<Affiliate> members) {
            super.onRoomMembersLoaded(members);
        }
    }

    // receives events from EventBus
    public void onEvent(Event event) {
        super.onEvent(event);
        switch (event.getType()) {
            case STICKER_SENT:
                cancelMessageNotificationsForChat();
                stickerSent(event.getImageName());
                break;

            case PRESENCE_RECEIVED:
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (mChat != null && mChat.getType() == Chat.TYPE_1_T0_1) {
                            setOneToOneChatConnectionStatus();
                        }
                    }
                });
                break;
        }
    }

    private void stickerSent(String imageName) {
        String messageId = RealmManager.getInstance()
                .saveMessageLocally(mChat, mChatJID, imageName, ChatMessage.TYPE_STICKER);
        mChat = getChatFromRealm();
        mRoomManager.sendStickerMessage(messageId, mChatJID, imageName, mChat.getType());
        stickersRecyclerView.setVisibility(View.GONE);
        refreshMessagesAndScrollToEnd();
    }

    private void manageLeaveAndContactMenuItems() {
        if (isChatWithContact()) {
            setMenuChatWithContact();
        } else {
            setMenuChatNotContact();
        }
    }

    private void setMenuChatNotContact() {
        mMenu.findItem(R.id.actionLeaveChat).setVisible(true);
        mMenu.findItem(R.id.actionAddToContacts).setVisible(true);
        mMenu.findItem(R.id.actionRemoveFromContacts).setVisible(false);
    }

    private void setMenuChatWithContact() {
        mMenu.findItem(R.id.actionLeaveChat).setVisible(false);
        mMenu.findItem(R.id.actionAddToContacts).setVisible(false);
        mMenu.findItem(R.id.actionRemoveFromContacts).setVisible(true);
    }

    private boolean isChatWithContact() {
        try {
            HashMap<Jid, Presence.Type> buddies = RosterManager.getInstance().getContacts();
            for (Map.Entry pair : buddies.entrySet()) {
                if (mChat.getJid().equals(pair.getKey().toString())) {
                    return true;
                }
            }
        } catch (SmackException.NotLoggedInException | InterruptedException | SmackException.NotConnectedException e) {
            Log.w(TAG, e);
        }
        return false;
    }

    private void setDestroyButtonVisibility(final Menu menu) {
        switch (mChat.getType()) {
            case Chat.TYPE_MUC_LIGHT:
                manageMUCLightAdmins(menu);
                break;
        }
    }

    private void manageMUCLightAdmins(final Menu menu) {
        Single<HashMap<Jid, MUCLightAffiliation>> task = Single.fromCallable(() -> {
            if (Preferences.isTesting()) {
                return null;
            }
            MultiUserChatLightManager multiUserChatLightManager = MultiUserChatLightManager.getInstanceFor(XMPPSession.getInstance().getXMPPConnection());
            MultiUserChatLight multiUserChatLight = multiUserChatLightManager.getMultiUserChatLight(JidCreate.from(mChatJID).asEntityBareJidIfPossible());
            return multiUserChatLight.getAffiliations();
        });

        addDisposable(task.subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(occupants -> {
                    for (Map.Entry<Jid, MUCLightAffiliation> pair : occupants.entrySet()) {
                        Jid key = pair.getKey();
                        if (key != null && key.toString().equals(Preferences.getInstance().getUserXMPPJid())) {
                            MenuItem destroyItem = menu.findItem(R.id.actionDestroyChat);
                            mIsOwner = pair.getValue().equals(MUCLightAffiliation.owner);
                            destroyItem.setVisible(mIsOwner && mChat.getType() == Chat.TYPE_MUC_LIGHT);
                        }
                    }
                }, error -> Log.w(TAG, error)));
    }

}


