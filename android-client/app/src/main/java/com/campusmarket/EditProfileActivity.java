package com.campusmarket;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import com.campusmarket.auth.SessionStore;
import com.campusmarket.network.UserApi;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EditProfileActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean destroyed;
    private boolean profileLoaded;
    private EditText nickname, school, campus, bio;
    private TextView status;
    private Button save;
    private SessionStore sessions;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_edit_profile);
        UiInsets.apply(findViewById(R.id.edit_root));
        nickname = findViewById(R.id.edit_nickname); school = findViewById(R.id.edit_school);
        campus = findViewById(R.id.edit_campus); bio = findViewById(R.id.edit_bio);
        status = findViewById(R.id.edit_status); save = findViewById(R.id.save_profile);
        sessions = new SessionStore(this);
        findViewById(R.id.edit_back).setOnClickListener(v -> finish());
        save.setOnClickListener(v -> save());
        String token = sessions.token();
        if (token == null) { finish(); return; }
        status.setText(R.string.loading_profile);
        executor.execute(() -> {
            try {
                UserApi.User user = new UserApi().me(token);
                handler.post(() -> {
                    if (destroyed) return;
                    nickname.setText(user.nickname); school.setText(user.school); campus.setText(user.campus); bio.setText(user.bio);
                    profileLoaded = true; status.setText(""); save.setEnabled(true);
                });
            } catch (IOException exception) { error(exception); }
        });
    }
    private void save() {
        String nick = nickname.getText().toString().trim();
        if (nick.isEmpty()) { status.setText(R.string.required_fields); return; }
        String schoolText = school.getText().toString().trim(), campusText = campus.getText().toString().trim(), bioText = bio.getText().toString().trim();
        String token = sessions.token();
        if (token == null) { finish(); return; }
        save.setEnabled(false); status.setText(R.string.submitting);
        executor.execute(() -> {
            try {
                new UserApi().update(token, nick, schoolText, campusText, bioText);
                handler.post(() -> { if (!destroyed) { Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show(); finish(); } });
            } catch (IOException exception) { error(exception); }
        });
    }
    private void error(IOException exception) {
        handler.post(() -> {
            if (destroyed) return;
            if (exception instanceof UserApi.Failure) {
                UserApi.Failure failure = (UserApi.Failure) exception;
                if (failure.status == 401 || failure.status == 403) {
                    sessions.clear(); Toast.makeText(this, R.string.session_expired, Toast.LENGTH_LONG).show(); finish(); return;
                }
                status.setText(failure.getMessage());
            } else { status.setText(R.string.request_network_error); }
            save.setEnabled(profileLoaded);
        });
    }
    @Override protected void onDestroy() { destroyed = true; executor.shutdownNow(); handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
