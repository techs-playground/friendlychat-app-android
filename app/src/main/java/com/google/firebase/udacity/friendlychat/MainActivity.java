/**
 * Copyright Google Inc. All Rights Reserved.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.firebase.udacity.friendlychat;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.firebase.ui.auth.AuthUI;
import com.firebase.ui.auth.AuthUI.IdpConfig.EmailBuilder;
import com.firebase.ui.auth.AuthUI.IdpConfig.GoogleBuilder;
import com.firebase.ui.auth.IdpResponse;
import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.util.ArrayList;
import java.util.List;

import static android.content.Intent.createChooser;
import static android.widget.Toast.LENGTH_SHORT;
import static android.widget.Toast.makeText;
import static com.google.firebase.udacity.friendlychat.FriendlyMessage.newPhoto;
import static com.google.firebase.udacity.friendlychat.FriendlyMessage.newText;
import static java.lang.String.format;
import static java.util.Arrays.asList;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private static final int RC_SIGN_IN = 1001;
    private static final int RC_PHOTO_PICKER = 1002;

    public static final String ANONYMOUS = "anonymous";
    public static final int DEFAULT_MSG_LENGTH_LIMIT = 1000;

    private ListView mMessageListView;
    private MessageAdapter messageAdapter;
    private ProgressBar mProgressBar;
    private ImageButton mPhotoPickerButton;
    private EditText mMessageEditText;
    private Button mSendButton;

    private String username;

    private FirebaseDatabase firebaseDatabase;
    private DatabaseReference messagesDatabaseReference;
    private ChildEventListener childEventListener;

    private FirebaseAuth firebaseAuth;
    private FirebaseAuth.AuthStateListener authStateListener;
    private FirebaseUser currentUser;

    private FirebaseStorage firebaseStorage;
    private StorageReference chatPhotosStorageReference;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        username = ANONYMOUS;

        firebaseDatabase = FirebaseDatabase.getInstance();
        messagesDatabaseReference = firebaseDatabase.getReference().child("messages");
        firebaseAuth = FirebaseAuth.getInstance();
        firebaseStorage = FirebaseStorage.getInstance();
        chatPhotosStorageReference = firebaseStorage.getReference().child("chat_photos");

        // Initialize references to views
        mProgressBar = findViewById(R.id.progressBar);
        mMessageListView = findViewById(R.id.messageListView);
        mPhotoPickerButton = findViewById(R.id.photoPickerButton);
        mMessageEditText = findViewById(R.id.messageEditText);
        mSendButton = findViewById(R.id.sendButton);

        // Initialize message ListView and its adapter
        List<FriendlyMessage> friendlyMessages = new ArrayList<>();
        messageAdapter = new MessageAdapter(this, R.layout.item_message, friendlyMessages);
        mMessageListView.setAdapter(messageAdapter);

        // Initialize progress bar
        mProgressBar.setVisibility(ProgressBar.INVISIBLE);

        // ImagePickerButton shows an image picker to upload a image for a message
        mPhotoPickerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startPickingPhoto();
            }
        });

        // Enable Send button when there's newText to send
        mMessageEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                if (charSequence.toString().trim().length() > 0) {
                    mSendButton.setEnabled(true);
                } else {
                    mSendButton.setEnabled(false);
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {
            }
        });
        mMessageEditText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(DEFAULT_MSG_LENGTH_LIMIT)});

        // Send button sends a message and clears the EditText
        mSendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                messagesDatabaseReference.push()
                        .setValue(newText(username, mMessageEditText.getText().toString()));

                // Clear input box
                mMessageEditText.setText("");
            }
        });

        authStateListener = new FirebaseAuth.AuthStateListener() {
            @Override
            public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
                currentUser = firebaseAuth.getCurrentUser();
                onAfterAuthStateChanged();
            }
        };
    }

    private void startPickingPhoto() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/jpeg");
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        startActivityForResult(createChooser(intent, "Complete action using"), RC_PHOTO_PICKER);
    }

    private void onAfterAuthStateChanged() {
        if (isUserLoggedIn()) {
            initializeUI();
        } else {
            cleanupUI();
            startSigningIn();
        }
    }

    private boolean isUserLoggedIn() {
        return currentUser != null;
    }

    private void initializeUI() {
        username = currentUser.getDisplayName();
        displayWelcomeMessage();
        attachDatabaseListener();
    }

    private void attachDatabaseListener() {
        if (childEventListener == null) {
            childEventListener = new ChildEventListener() {
                @Override
                public void onChildAdded(@NonNull DataSnapshot dataSnapshot, @Nullable String s) {
                    FriendlyMessage message = dataSnapshot.getValue(FriendlyMessage.class);
                    messageAdapter.add(message);
                }

                public void onChildChanged(@NonNull DataSnapshot dataSnapshot, @Nullable String s) {}
                public void onChildRemoved(@NonNull DataSnapshot dataSnapshot) {}
                public void onChildMoved(@NonNull DataSnapshot dataSnapshot, @Nullable String s) {}
                public void onCancelled(@NonNull DatabaseError databaseError) { }
            };
            messagesDatabaseReference.addChildEventListener(childEventListener);
        }
    }

    private void cleanupUI() {
        detachDatabaseListener();
        messageAdapter.clear();
    }

    private void detachDatabaseListener() {
        if (childEventListener != null) {
            messagesDatabaseReference.removeEventListener(childEventListener);
            childEventListener = null;
        }
    }

    private void startSigningIn() {
        Log.d(TAG, "Starting sign in flow");
        List<AuthUI.IdpConfig> providers = asList(
                new EmailBuilder().build(),
                new GoogleBuilder().build()
        );

        Intent signInIntent = AuthUI.getInstance()
                .createSignInIntentBuilder()
                .setAvailableProviders(providers)
                .setLogo(R.mipmap.ic_launcher)
                .build();
        startActivityForResult(signInIntent, RC_SIGN_IN);
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
                startSigningOut();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void startSigningOut() {
        Log.d(TAG, "Starting sign out process");
        AuthUI.getInstance().signOut(this)
                .addOnCompleteListener(new OnCompleteListener<Void>() {
                    @Override
                    public void onComplete(@NonNull Task<Void> task) {
                        displayGoodByeMessage();
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        firebaseAuth.addAuthStateListener(authStateListener);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (authStateListener != null) {
            firebaseAuth.removeAuthStateListener(authStateListener);
        }
        cleanupUI();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case RC_SIGN_IN:
                onAfterSignInResult(data, resultCode);
                break;
            case RC_PHOTO_PICKER:
                onPhotoPickerResult(data);
                break;
        }
    }

    private void onPhotoPickerResult(@Nullable Intent data) {
        if (data != null && data.getData() != null) {
            Uri selectedPhotoUri = data.getData();
            uploadPhotoToStorage(selectedPhotoUri);
        }
    }

    private void uploadPhotoToStorage(Uri selectedPhoto) {
        final StorageReference child = chatPhotosStorageReference.child(getFileName(selectedPhoto));
        UploadTask uploadTask = child.putFile(selectedPhoto);
        uploadTask.continueWithTask(new Continuation<UploadTask.TaskSnapshot, Task<Uri>>() {
            @Override
            public Task<Uri> then(@NonNull Task<UploadTask.TaskSnapshot> task) throws Exception {
                if (!task.isSuccessful()) {
                    throw task.getException();
                }
                return child.getDownloadUrl();
            }
        }).addOnCompleteListener(new OnCompleteListener<Uri>() {
            @Override
            public void onComplete(@NonNull Task<Uri> task) {
                if (task.isSuccessful()) {
                    sendPhotoUrlMessage(task.getResult());
                }
            }
        });
    }

    public String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    private void sendPhotoUrlMessage(Uri downloadUri) {
        String downloadUrl = downloadUri.toString();
        messagesDatabaseReference.push().setValue(newPhoto(username, downloadUrl));
    }

    private void onAfterSignInResult(Intent data, int resultCode) {
        if (resultCode != RESULT_OK) {
            if (!handleSignInError(data)) {
                displayGoodByeMessage();
            }

            finish();
        }
    }

    private boolean handleSignInError(Intent data) {
        IdpResponse idpResponse = IdpResponse.fromResultIntent(data);

        if (idpResponse == null) return false;

        if (idpResponse.getError() != null) {
            //TODO keep track of the error, e.g. Firebase Crashlytics
            Log.e(TAG, idpResponse.getError().getMessage());
        }

        displaySorryMessage();

        return true;
    }

    private void displaySorryMessage() {
        makeText(this, getString(R.string.sorry_message), Toast.LENGTH_LONG).show();
    }

    private void displayWelcomeMessage() {
        makeText(this, format(getString(R.string.welcome_message), username,
                getString(R.string.app_name)), LENGTH_SHORT)
                .show();
    }

    private void displayGoodByeMessage() {
        makeText(this, getString(R.string.good_bye_message), LENGTH_SHORT).show();
    }

}
