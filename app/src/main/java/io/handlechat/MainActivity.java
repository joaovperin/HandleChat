/*
 * Copyright (C) 2019 HandleChat
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.handlechat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.Config;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.firebase.ui.auth.AuthUI;
import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Main activity
 *
 * @author joaovperin
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    public static final String KEY_MESSAGE_LENGTH = "max_message_length";
    public static final String KEY_DEFAULT_CHAT_ROOM = "default_chat_room";
    public static final String KEY_NUMBER_MESSAGES = "number_messages";

    private static final String ANONYMOUS = "anonymous";

    private Long mFirebaseConfigCacheExpiration;

    private static final int RC_SIGN_IN = 100001;
    private static final int RC_PHOTO_PICKER = 10002;

    private ChatMessageAdapter mMessageAdapter;
    private EditText mMessageEditText;
    private Button mSendButton;

    private FirebaseDatabase mFirebaseDatabase;
    private DatabaseReference mMessagesDatabaseReference;
    private ChildEventListener mChildEventListener;
    private FirebaseAuth mFirebaseAuth;
    private FirebaseAuth.AuthStateListener mAuthStateListener;
    private FirebaseStorage mFirebaseStorage;
    private StorageReference mChatPhotosStorageReference;
    private FirebaseRemoteConfig mFirebaseRemoteConfig;

    private String mChatRoom;
    private String mUsername;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Default username
        mUsername = ANONYMOUS;
        mChatRoom = "default";

        // Initialize firebase database and auth
        mFirebaseDatabase = FirebaseDatabase.getInstance();
        mFirebaseAuth = FirebaseAuth.getInstance();
        mFirebaseStorage = FirebaseStorage.getInstance();
        mFirebaseRemoteConfig = FirebaseRemoteConfig.getInstance();

        // Initialize references to views
        ProgressBar mProgressBar = findViewById(R.id.progressBar);
        ListView mMessageListView = findViewById(R.id.messageListView);
        mMessageEditText = findViewById(R.id.messageEditText);
        mSendButton = findViewById(R.id.sendButton);

        // Initialize message ListView and its adapter
        List<ChatMessage> ChatMessages = new ArrayList<>();
        mMessageAdapter = new ChatMessageAdapter(this, R.layout.item_message, ChatMessages);
        mMessageListView.setAdapter(mMessageAdapter);

        // Initialize progress bar
        mProgressBar.setVisibility(ProgressBar.INVISIBLE);

        // Enable Send button when there's text to send
        mMessageEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                final boolean enableSendButton = (charSequence.toString().trim().length() > 0);
                mSendButton.setEnabled(enableSendButton);
            }

            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });

        // Loads all the configuration
        FirebaseRemoteConfigSettings configSettings = new FirebaseRemoteConfigSettings.Builder()
                .setDeveloperModeEnabled(Config.DEBUG).build();
        mFirebaseRemoteConfig.setConfigSettings(configSettings);
        // Sets defaults for configs
        HashMap<String, Object> defaultConfigs = new HashMap<>();
        defaultConfigs.put(KEY_MESSAGE_LENGTH, 200);
        defaultConfigs.put(KEY_DEFAULT_CHAT_ROOM, "default");
        defaultConfigs.put(KEY_NUMBER_MESSAGES, 15);
        mFirebaseRemoteConfig.setDefaults(defaultConfigs);

        // Configures the cache
        mFirebaseConfigCacheExpiration = 3600L;
        if (mFirebaseRemoteConfig.getInfo().getConfigSettings().isDeveloperModeEnabled()) {
            mFirebaseConfigCacheExpiration = 0L;
        }

        // Loads configuration
        fetchSettings(new OnCompleteListener<Void>() {
            @Override
            public void onComplete(@NonNull Task<Void> task) {
                // Sets max messages length
                Long messagesMaxLength = mFirebaseRemoteConfig.getLong(KEY_MESSAGE_LENGTH);
                mMessageEditText
                        .setFilters(new InputFilter[]{new InputFilter.LengthFilter(messagesMaxLength.intValue())});
                // Default chat room
                mChatRoom = mFirebaseRemoteConfig.getString(KEY_DEFAULT_CHAT_ROOM);
            }
        });

        // Puts an AuthStateListener
        mAuthStateListener = new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
                FirebaseUser user = firebaseAuth.getCurrentUser();
                if (user != null) {
                    onSignInInInitialize(user);
                } else {
                    onSignedOutCleanup();
                    startActivityForResult(createIntentForSigning(), RC_SIGN_IN);
                }
            }
        };
    }

    private void fetchSettings(final OnCompleteListener<Void> listener) {
        final Task<Void> task = mFirebaseRemoteConfig.fetch(mFirebaseConfigCacheExpiration);
        task.addOnSuccessListener(new OnSuccessListener<Void>() {
            @Override
            public void onSuccess(Void aVoid) {
                mFirebaseRemoteConfig.activate();
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
                Log.w(TAG, "Error fetching config,. Falling back to default", e);
            }
        }).addOnCompleteListener(listener);
    }

    private Intent createIntentForSigning() {
        return AuthUI.getInstance().createSignInIntentBuilder().setIsSmartLockEnabled(false, true)
                .setAvailableProviders(Arrays.asList(
                        new AuthUI.IdpConfig.EmailBuilder().build(),
                        new AuthUI.IdpConfig.GoogleBuilder().build()
                ))
                .build();
    }

    private void uploadPicture(Uri selectedImageUri) {
        final StorageReference photoRef = mChatPhotosStorageReference.child(selectedImageUri.getLastPathSegment());

        photoRef.putFile(selectedImageUri).continueWithTask(new Continuation<UploadTask.TaskSnapshot, Task<Uri>>() {
            @Override
            public Task<Uri> then(@NonNull Task<UploadTask.TaskSnapshot> task) throws Exception {
                if (!task.isSuccessful()) {
                    throw task.getException();
                }
                return photoRef.getDownloadUrl();
            }
        }).addOnCompleteListener(new OnCompleteListener<Uri>() {
            @Override
            public void onComplete(@NonNull Task<Uri> task) {
                if (task.isSuccessful()) {
                    ChatMessage ChatMessage = new ChatMessage(null, mUsername, task.getResult().toString());
                    mMessagesDatabaseReference.push().setValue(ChatMessage);
                } else {
                    Toast.makeText(MainActivity.this, getString(R.string.upload_fail) + task.getException().getMessage(),
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void onSignedOutCleanup() {
        mUsername = ANONYMOUS;
        mMessageAdapter.clear();
        detachDatabaseReadListener();
    }

    private void detachDatabaseReadListener() {
        if (mChildEventListener != null) {
            mMessagesDatabaseReference.removeEventListener(mChildEventListener);
            mChildEventListener = null;
        }
    }

    private void onSignInInInitialize(final FirebaseUser firebaseUser) {
        mUsername = firebaseUser.getDisplayName();

        // Instantiate the users database
        DatabaseReference mUsersDatabaseReference = mFirebaseDatabase.getReference().child("users");

        mUsersDatabaseReference.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (dataSnapshot.hasChild(firebaseUser.getUid())) {
                    mChatRoom = dataSnapshot.child(firebaseUser.getUid()).child("chatroom").getValue(String.class);
                }
                attachDatabaseReadListener();
                loadInitialMessages();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                attachDatabaseReadListener();
                loadInitialMessages();
            }
        });

    }

    private void loadInitialMessages() {
        // Load some data on activity create
        Long numberMessages = mFirebaseRemoteConfig.getLong(KEY_NUMBER_MESSAGES);
        mMessagesDatabaseReference.orderByKey().limitToLast(numberMessages.intValue());
    }

    private void attachDatabaseReadListener() {
        // Instantiate the databases
        mMessagesDatabaseReference = mFirebaseDatabase.getReference().child("chat").child(mChatRoom).child("messages");
        mChatPhotosStorageReference = mFirebaseStorage.getReference().child("chat_photos").child(mChatRoom);
        //
        if (mChildEventListener == null) {
            mChildEventListener = new ChildEventListener() {
                @Override
                public void onChildAdded(@NonNull DataSnapshot dataSnapshot, @Nullable String s) {
                    ChatMessage value = dataSnapshot.getValue(ChatMessage.class);
                    mMessageAdapter.add(value);
                }

                @Override
                public void onChildChanged(@NonNull DataSnapshot dataSnapshot, @Nullable String s) {
                }

                @Override
                public void onChildRemoved(@NonNull DataSnapshot dataSnapshot) {
                }

                @Override
                public void onChildMoved(@NonNull DataSnapshot dataSnapshot, @Nullable String s) {
                }

                @Override
                public void onCancelled(@NonNull DatabaseError databaseError) {
                }
            };
            mMessagesDatabaseReference.addChildEventListener(mChildEventListener);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.sign_out_menu:
                AuthUI.getInstance().signOut(MainActivity.this).addOnCompleteListener(new OnCompleteListener<Void>() {
                    @Override
                    public void onComplete(@NonNull Task<Void> task) {
                        startActivityForResult(createIntentForSigning(), RC_SIGN_IN);
                        finish();
                    }
                });
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mFirebaseAuth.addAuthStateListener(mAuthStateListener);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mAuthStateListener != null) {
            mFirebaseAuth.removeAuthStateListener(mAuthStateListener);
        }
        detachDatabaseReadListener();
        mMessageAdapter.clear();
    }

    public void onClickPhotoPickerButton(View v) {
        // ImagePickerButton shows an image picker to upload a image for a message
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/jpeg");
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        startActivityForResult(Intent.createChooser(intent, "Complete action using"), RC_PHOTO_PICKER);
    }

    public void onClickChangeButton(View v) {
        // Send button sends a message and clears the EditText
        ChatMessage message = new ChatMessage(mMessageEditText.getText().toString(), mUsername, null);
        mMessagesDatabaseReference.push().setValue(message);
        // Clear input box
        mMessageEditText.setText("");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case RC_SIGN_IN:
                if (resultCode == RESULT_OK) {
                    Toast.makeText(MainActivity.this, getString(R.string.welcome_to_app), Toast.LENGTH_LONG).show();
                } else if (resultCode == RESULT_CANCELED) {
                    Toast.makeText(MainActivity.this, getString(R.string.sign_in_canceled), Toast.LENGTH_LONG).show();
                    finish();
                }
                break;
            case RC_PHOTO_PICKER:
                if (resultCode == RESULT_OK) {
                    Uri selectedImageUri = data.getData();
                    uploadPicture(selectedImageUri);
                } else if (resultCode != RESULT_CANCELED) {
                    Toast.makeText(MainActivity.this, getString(R.string.fail_load_image), Toast.LENGTH_SHORT).show();
                }
                break;
        }

    }
}
