package com.campusmarket;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import com.campusmarket.auth.SessionStore;
import com.campusmarket.network.UserApi;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AuthActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean register, destroyed;
    private EditText username, password, confirm, nickname;
    private TextView status, title;
    private Button submit, switchMode;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_auth);
        UiInsets.apply(findViewById(R.id.auth_root));
        username = findViewById(R.id.username); password = findViewById(R.id.password);
        confirm = findViewById(R.id.confirm_password); nickname = findViewById(R.id.nickname);
        status = findViewById(R.id.auth_status); title = findViewById(R.id.auth_title);
        submit = findViewById(R.id.auth_submit); switchMode = findViewById(R.id.auth_switch);
        findViewById(R.id.auth_back).setOnClickListener(v -> finish());
        switchMode.setOnClickListener(v -> { register = !register; status.setText(""); showMode(); });
        submit.setOnClickListener(v -> submit());
        register = saved != null && saved.getBoolean("register");
        showMode();
    }
    private void showMode() {
        title.setText(register ? R.string.register_title : R.string.login_title);
        submit.setText(register ? R.string.register : R.string.login);
        switchMode.setText(register ? R.string.switch_login : R.string.switch_register);
        confirm.setVisibility(register ? View.VISIBLE : View.GONE);
        nickname.setVisibility(register ? View.VISIBLE : View.GONE);
        password.setText(""); confirm.setText("");
    }
    private void submit() {
        String name = username.getText().toString().trim();
        String secret = password.getText().toString();
        String confirmation = confirm.getText().toString();
        String nick = nickname.getText().toString().trim();
        if (name.isEmpty() || secret.isEmpty() || (register && nick.isEmpty())) { status.setText(R.string.required_fields); return; }
        if (register && !secret.equals(confirmation)) { status.setText(R.string.password_mismatch); return; }
        boolean registering = register;
        submit.setEnabled(false); switchMode.setEnabled(false); status.setText(R.string.submitting);
        executor.execute(() -> {
            try {
                UserApi api = new UserApi();
                if (registering) api.register(name, secret, confirmation, nick);
                else {
                    UserApi.Login login = api.login(name, secret);
                    new SessionStore(this).save(login.token, login.expiresAt);
                }
                handler.post(() -> {
                    if (destroyed) return;
                    submit.setEnabled(true); switchMode.setEnabled(true);
                    if (registering) {
                        register = false; showMode(); status.setText(R.string.registration_success);
                    } else { password.setText(""); setResult(RESULT_OK); finish(); }
                });
            } catch (UserApi.Failure failure) { showError(failure.getMessage()); }
            catch (IOException exception) { showError(getString(R.string.request_network_error)); }
        });
    }
    private void showError(String message) {
        handler.post(() -> { if (!destroyed) { status.setText(message); submit.setEnabled(true); switchMode.setEnabled(true); } });
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putBoolean("register", register); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() {
        destroyed = true; executor.shutdownNow(); handler.removeCallbacksAndMessages(null); super.onDestroy();
    }
}
