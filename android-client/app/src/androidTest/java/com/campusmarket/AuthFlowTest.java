package com.campusmarket;

import static org.junit.Assert.assertTrue;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.campusmarket.auth.SessionStore;
import com.campusmarket.network.UserApi;
import com.google.gson.Gson;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises real UI and real HTTP. Runtime-generated credentials never enter ADB arguments or output. */
@RunWith(AndroidJUnit4.class)
public final class AuthFlowTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @Test public void registrationLoginProfileLogoutAndEncryptedSession() throws Exception {
        String name = "p3_ui_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        byte[] random = new byte[24]; new SecureRandom().nextBytes(random);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        new SessionStore(context).clear();
        MainActivity main = startMain();
        tap(main, R.id.nav_profile);
        AuthActivity auth = openAuth(main);
        tap(auth, R.id.auth_switch);
        fill(auth, R.id.username, name); fill(auth, R.id.password, password);
        fill(auth, R.id.confirm_password, "mismatch"); fill(auth, R.id.nickname, "界面验收用户");
        tap(auth, R.id.auth_submit);
        await(() -> text(auth, R.id.auth_status).equals(context.getString(R.string.password_mismatch)), "confirmation mismatch feedback");
        fill(auth, R.id.confirm_password, password); tap(auth, R.id.auth_submit);
        await(() -> text(auth, R.id.auth_status).equals(context.getString(R.string.registration_success)), "registration via UI");
        tap(auth, R.id.auth_switch);
        fill(auth, R.id.password, password); fill(auth, R.id.confirm_password, password);
        tap(auth, R.id.auth_submit);
        await(() -> text(auth, R.id.auth_status).equals("用户名已被使用"), "duplicate username feedback");
        tap(auth, R.id.auth_switch);
        fill(auth, R.id.password, password + "wrong"); tap(auth, R.id.auth_submit);
        await(() -> text(auth, R.id.auth_status).equals("用户名或密码错误"), "wrong password feedback");
        fill(auth, R.id.password, password); tap(auth, R.id.auth_submit);
        await(() -> text(main, R.id.profile_details).contains(name), "login displays protected profile");
        String token = new SessionStore(context).token();
        assertTrue("encrypted session can be restored", token != null);
        String preferences = context.getSharedPreferences("session", Context.MODE_PRIVATE).getAll().toString();
        assertTrue("preferences contain neither token plaintext nor password", !preferences.contains(token) && !preferences.contains(password));
        Instrumentation.ActivityMonitor editMonitor = instrumentation.addMonitor(EditProfileActivity.class.getName(), null, false);
        tap(main, R.id.edit_profile);
        EditProfileActivity edit = (EditProfileActivity) instrumentation.waitForMonitorWithTimeout(editMonitor, 5000);
        instrumentation.removeMonitor(editMonitor);
        assertTrue("profile editor opened", edit != null);
        await(() -> onMain(() -> edit.findViewById(R.id.save_profile).isEnabled()), "profile loaded before editing");
        fill(edit, R.id.edit_nickname, "界面验收同学"); fill(edit, R.id.edit_school, "校园易物测试大学");
        fill(edit, R.id.edit_campus, "南校区"); fill(edit, R.id.edit_bio, "由Android界面真实提交");
        tap(edit, R.id.save_profile);
        await(() -> text(main, R.id.profile_details).contains("南校区"), "edited profile reloaded");
        UserApi.User user = new UserApi().me(token);
        assertTrue("profile persisted through separate HTTP call", "界面验收同学".equals(user.nickname) && "南校区".equals(user.campus));
        File directory = context.getExternalFilesDir(null);
        assertTrue("evidence directory available", directory != null);
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        try (FileOutputStream output = new FileOutputStream(new File(directory, "phase3-profile.png"))) {
            assertTrue("profile screenshot saved", screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { screenshot.recycle(); }
        tap(main, R.id.logout);
        await(() -> onMain(() -> main.findViewById(R.id.account_login).getVisibility() == View.VISIBLE), "logout UI state");
        assertTrue("logout clears encrypted session", new SessionStore(context).token() == null);
        boolean rejected = false;
        try { new UserApi().me(token); } catch (UserApi.Failure failure) { rejected = failure.status == 401; }
        assertTrue("logout revokes server token", rejected);
        AuthActivity loginAgain = openAuth(main);
        fill(loginAgain, R.id.username, name); fill(loginAgain, R.id.password, password); tap(loginAgain, R.id.auth_submit);
        await(() -> text(main, R.id.profile_details).contains(name), "login again after logout");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("passed", true); summary.put("username", name); summary.put("userId", user.id);
        summary.put("nickname", "界面验收同学"); summary.put("campus", "南校区");
        summary.put("confirmationMismatchHandled", true); summary.put("duplicateHandled", true);
        summary.put("wrongPasswordRejected", true); summary.put("encryptedSessionVerified", true);
        summary.put("profilePersisted", true); summary.put("logoutRevocationVerified", true);
        try (FileOutputStream output = new FileOutputStream(new File(directory, "phase3-ui-result.json"))) {
            output.write(new Gson().toJson(summary).getBytes(StandardCharsets.UTF_8));
        }
    }
    private MainActivity startMain() {
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return (MainActivity) instrumentation.startActivitySync(intent);
    }
    private AuthActivity openAuth(MainActivity main) {
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(AuthActivity.class.getName(), null, false);
        tap(main, R.id.account_login);
        AuthActivity activity = (AuthActivity) instrumentation.waitForMonitorWithTimeout(monitor, 5000);
        instrumentation.removeMonitor(monitor);
        assertTrue("login page opened", activity != null);
        instrumentation.waitForIdleSync();
        return activity;
    }
    private void tap(Activity activity, int id) { onMain(() -> { activity.findViewById(id).performClick(); return null; }); }
    private void fill(Activity activity, int id, String value) { onMain(() -> { ((EditText) activity.findViewById(id)).setText(value); return null; }); }
    private String text(Activity activity, int id) { return onMain(() -> ((TextView) activity.findViewById(id)).getText().toString()); }
    private <T> T onMain(Callable<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { result.set(action.call()); } catch (Exception exception) { throw new AssertionError("UI action failed"); } });
        return result.get();
    }
    private void await(BooleanSupplier condition, String label) throws InterruptedException {
        for (int attempt = 0; attempt < 150; attempt++) { if (condition.getAsBoolean()) return; Thread.sleep(100); }
        assertTrue(label, false);
    }
}
